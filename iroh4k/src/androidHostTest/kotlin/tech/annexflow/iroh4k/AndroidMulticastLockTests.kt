package tech.annexflow.iroh4k

import android.content.Context
import android.content.ContextWrapper
import android.net.wifi.WifiManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWifiManager

/**
 * Exercises [Iroh4kAndroid.multicastLock].
 *
 * **This is the one class in `androidHostTest` that is not a delegator.** Every other class here
 * constructs a `Common*Tests` runner and forwards one `@Test` per shared body, because every other
 * facade has to behave identically and the bodies therefore live once in `commonTest`. There is no
 * shared body to delegate to for this one: the multicast lock is `androidMain` code over an Android
 * framework API, so `jvmTest` and the native tests have nothing to run and no way to run it. The
 * `@RunWith`/`@Config` pair is not decorative here either — unlike the delegators, whose bodies
 * touch no Android API, these tests are entirely about one, and without Robolectric there is no
 * `WifiManager` to get.
 *
 * ## What is actually observable
 *
 * Robolectric's `ShadowMulticastLock` models the lock properly — a reference count, a
 * non-reference-counted `locked` flag, `isHeld()` reading whichever applies — but it hands the lock
 * object to the caller and keeps no registry of them, and [Iroh4kAndroid.multicastLock] deliberately
 * does not hand its lock out. So `isHeld()` is out of reach from here. What is in reach is
 * [ShadowWifiManager.getActiveLockCount], which `ShadowMulticastLock.acquire` increments and
 * `release` decrements against the `WifiManager` the lock came from: it observes the same two calls
 * from the other side, and it can go *negative*, which is what makes it the right instrument for the
 * double-close test — a second release that reached the framework would show up as `-1` rather than
 * as nothing at all.
 *
 * What is not covered, and cannot be: the missing-permission path. On a device `acquire()` throws
 * `SecurityException` from `WifiServiceImpl.acquireMulticastLock`'s
 * `enforceMulticastChangePermission()`, but Robolectric's shadow replaces the binder call outright
 * and checks no permission, so there is nothing here to assert against. See the `@throws` on
 * [Iroh4kAndroid.multicastLock] for how that behaviour was established instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidMulticastLockTests {

    private val application: Context get() = RuntimeEnvironment.getApplication()

    private fun wifiShadow(): ShadowWifiManager =
        shadowOf(application.getSystemService(WifiManager::class.java))

    @Test
    fun `the lock is acquired before the handle is returned`() {
        val wifi = wifiShadow()
        val baseline = wifi.activeLockCount

        val handle = Iroh4kAndroid.multicastLock(application)
        try {
            assertEquals(baseline + 1, wifi.activeLockCount, "acquire() should have been called")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `closing the handle releases the lock`() {
        val wifi = wifiShadow()
        val baseline = wifi.activeLockCount

        val handle = Iroh4kAndroid.multicastLock(application)
        handle.close()

        assertEquals(baseline, wifi.activeLockCount, "close() should have released the lock")
    }

    @Test
    fun `closing a second time is harmless`() {
        val wifi = wifiShadow()
        val baseline = wifi.activeLockCount

        val handle = Iroh4kAndroid.multicastLock(application)
        handle.close()
        handle.close()
        handle.close()

        // Not merely "did not throw": an unbalanced release would drive the count below the
        // baseline, which is exactly the lock-somebody-else-acquired failure the guard exists for.
        assertEquals(baseline, wifi.activeLockCount, "only the first close() may release")
    }

    @Test
    fun `use releases the lock on the way out`() {
        val wifi = wifiShadow()
        val baseline = wifi.activeLockCount

        Iroh4kAndroid.multicastLock(application).use {
            assertEquals(baseline + 1, wifi.activeLockCount, "held for the duration of the block")
        }

        assertEquals(baseline, wifi.activeLockCount, "use { } should have closed the handle")
    }

    @Test
    fun `use releases the lock when the block throws`() {
        val wifi = wifiShadow()
        val baseline = wifi.activeLockCount

        val thrown = runCatching {
            Iroh4kAndroid.multicastLock(application).use { error("boom") }
        }.exceptionOrNull()

        assertEquals("boom", thrown?.message, "the block's own failure should propagate")
        assertEquals(baseline, wifi.activeLockCount, "and the lock should still be released")
    }

    @Test
    fun `the lock is taken from the application context, not the one passed in`() {
        val wifi = wifiShadow()
        val baseline = wifi.activeLockCount

        // A MulticastLock outlives the screen that wanted it more often than not, so one resolved
        // from an Activity context would pin that Activity for as long as it is held. This wrapper
        // fails the test if the passed context is consulted for services at all; its
        // getApplicationContext(), inherited from ContextWrapper, still yields the real one.
        val hostile = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any =
                fail("multicastLock resolved WifiManager from the context it was passed")
        }

        Iroh4kAndroid.multicastLock(hostile).use {
            assertEquals(baseline + 1, wifi.activeLockCount)
        }
        assertEquals(baseline, wifi.activeLockCount)
    }
}
