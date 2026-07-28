package tech.annexflow.iroh4k

import android.content.Context
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android-only half of iroh4k — the two things that are Android rather than JNI, and so have no
 * counterpart on the JVM target that shares the same facade.
 *
 * - [install] hands the process's `JavaVM` and application `Context` to the native side, once,
 *   before the first [Endpoint]. **Consumers do not normally call it**: [Iroh4kInitializer] runs it
 *   through `androidx.startup` when the process starts, so an app that just depends on the AAR is
 *   already initialised. It is public for the two cases where that does not happen — an app that
 *   removed the startup provider from its manifest, and an embedder that creates iroh4k in a process
 *   App Startup does not run in.
 * - [multicastLock] holds the `WifiManager.MulticastLock` without which [MdnsConfig] discovers
 *   nothing on this platform. Unlike [install] there is nothing automatic about it, because it costs
 *   battery and needs a permission only the app can declare.
 *
 * ## What [install] installs, and why the library aborts without it
 *
 * Crates underneath iroh reach the Android platform through `ndk_context` — `hickory-resolver` for
 * the system DNS servers, `netdev` for the interface list — and that crate has to be handed the
 * process's `JavaVM` and application `Context` before anything asks it for them. Nothing in the
 * NDK does that for a library loaded by an ordinary app, so without this call the first
 * `Endpoint.bind` ends the process:
 *
 * ```text
 * Fatal signal 6 (SIGABRT) in tid (tokio-rt-worker)
 * Abort message: 'android context was not initialized'
 * ```
 *
 * That is a native abort, not an exception: `panic = "abort"` is deliberate in the Rust profile
 * because unwinding across the FFI boundary is undefined behaviour. So there is nothing for Kotlin
 * to catch, which is exactly why initialisation is automatic rather than documented.
 *
 * The stored `Context` is the *application* context regardless of what is passed in, so nothing
 * here can outlive-leak an Activity.
 */
object Iroh4kAndroid {

    /**
     * Installs the process's `JavaVM` and application `Context`.
     *
     * Idempotent and safe from any thread: the native side installs on the first successful call
     * and every later call returns that same outcome without touching anything.
     *
     * @throws IllegalStateException if the JNI handles could not be obtained, which would
     * otherwise surface much later as the abort above, on a thread with no connection to the code
     * that caused it.
     */
    fun install(context: Context) {
        check(Iroh4kAndroidJni.installContext(context.applicationContext)) {
            "iroh4k could not install the Android context. Endpoints cannot be created in this " +
                    "process; see Iroh4kAndroid."
        }
    }

    /**
     * Holds a `WifiManager.MulticastLock` for as long as the returned handle is open, which is what
     * makes [MdnsConfig] discovery work on Android at all.
     *
     * ## Why mDNS finds nothing without it
     *
     * mDNS is multicast: every announcement and every query is a UDP datagram addressed to a group
     * — `224.0.0.251` or `ff02::fb` — rather than to this device. To keep the application processor
     * asleep, Android's Wi-Fi stack filters exactly that traffic out; multicast and broadcast frames
     * not explicitly addressed to this device are dropped below the socket layer. A `MulticastLock`
     * is the switch that lifts the filter, and the platform's own documentation is blunt about why
     * it is a switch rather than the default: "Processing these extra packets can cause a noticable
     * battery drain and should be disabled when not needed."
     *
     * The failure without the lock is the worst kind, because nothing fails. The endpoint binds, the
     * discovery service starts, this device's own announcements go out — and no peer's ever comes
     * back, so a [connect] given nothing but an [EndpointId] simply never resolves it. There is no
     * exception to catch, no error code, no log line. That is the whole reason this helper exists
     * rather than a paragraph in the documentation, and it is why [MdnsConfig] lists the requirement
     * among its caveats instead of assuming it will be discovered.
     *
     * ## The permission is yours to declare
     *
     * The lock needs `android.permission.CHANGE_WIFI_MULTICAST_STATE`, and iroh4k deliberately does
     * **not** put it in the AAR's manifest — unlike `INTERNET` and `ACCESS_NETWORK_STATE`, which it
     * does. Those two are declared for you because nothing in the library works without them and
     * their absence is opaque. This one is different on both counts: iroh4k is completely functional
     * without it as long as [EndpointConfig.mdns] is `null`, which is the default, and a Wi-Fi
     * permission is visible to anyone reading the merged manifest or a store listing. A library that
     * quietly added one to every app depending on it, the large majority of which never ask for
     * mDNS, would be spending someone else's reputation to save them a line. So it is your line:
     *
     * ```xml
     * <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
     * ```
     *
     * It is an install-time permission with no runtime prompt, so declaring it is the whole of the
     * work — there is nothing to request and nothing for the user to refuse. Omitting it makes this
     * call throw rather than degrade quietly; see the `@throws` below, which documents exactly what
     * comes out and where from.
     *
     * ## Hold it while discovery matters, not for the process lifetime
     *
     * With the filter lifted, every multicast and broadcast frame on the link is carried up to the
     * CPU — mDNS from every printer, TV and laptop on the network, SSDP, and whatever else is
     * chattering — and it keeps being carried up while your app is in the background. So this does
     * not belong in `Application.onCreate`. Bracket the window in which finding a peer actually
     * matters: a pairing screen, a transfer waiting for a LAN address, the first seconds after a
     * user asks to see nearby devices.
     *
     * `AutoCloseable` is what makes that easy, and `use { }` is the idiom for it throughout iroh4k:
     *
     * ```kotlin
     * Iroh4kAndroid.multicastLock(context).use {
     *     Endpoint.bind(EndpointConfig(mdns = MdnsConfig())).use { endpoint ->
     *         endpoint.connect(EndpointAddr.of(peerId), alpn).use { connection -> … }
     *     }
     * }
     * ```
     *
     * Acquire it before binding rather than after, so the endpoint hears the announcements already
     * on the link instead of waiting for the next round of them.
     *
     * The lock concerns the Wi-Fi interface and nothing else. Over Ethernet or on an emulator it
     * changes nothing, and holding it there is harmless rather than wrong — the platform offers no
     * way to ask whether it is doing anything, so there is nothing to branch on.
     *
     * @param context any [Context]. The **application** context is what is actually used, exactly as
     *   in [install]: a lock outlives the screen that wanted it far more often than not, and one
     *   keyed to an Activity would keep that Activity alive for as long as it is held.
     * @param tag the name the platform records the lock under. `WifiServiceImpl` logs it on acquire
     *   and `WifiMulticastLockManager` reports it in `adb shell dumpsys wifi`, so it is worth making
     *   it specific if an app holds more than one.
     * @return a handle whose [AutoCloseable.close] releases the lock. Closing more than once is
     *   harmless.
     * @throws SecurityException if the app has not declared `CHANGE_WIFI_MULTICAST_STATE`. It comes
     *   out of `acquire()`, not out of `createMulticastLock` — the latter is a plain constructor
     *   call in `WifiManager` that reaches no service and checks nothing. `acquire()` makes a binder
     *   call to `WifiServiceImpl.acquireMulticastLock`, whose first statement is
     *   `enforceMulticastChangePermission()`, which is `Context.enforceCallingOrSelfPermission` and
     *   throws; `MulticastLock.acquire` catches only `RemoteException`, so it crosses back to the
     *   caller unchanged. The message names the permission —
     *   `WifiService: Neither user <uid> nor current process has
     *   android.permission.CHANGE_WIFI_MULTICAST_STATE.` — which makes this the one part of the
     *   Android mDNS story that diagnoses itself. Established by reading AOSP's `WifiServiceImpl`,
     *   `ContextImpl.enforce` and the API 34 `WifiManager.MulticastLock` bytecode, because the SDK
     *   documentation states the permission but not what happens without it. Robolectric's
     *   `ShadowWifiManager` performs no permission check, so this path is not covered by a host
     *   test.
     * @throws IllegalStateException on a device with no Wi-Fi at all, where
     *   `getSystemService(WifiManager::class.java)` returns `null` — the Wi-Fi module registers its
     *   service wrapper behind a `PackageManager.FEATURE_WIFI` check and hands back `null` when the
     *   feature is absent. This is reported rather than swallowed because a caller reaching for the
     *   lock believed mDNS was going to work. If you support Ethernet-only devices, catch it and
     *   carry on: there is no Wi-Fi multicast filter there to lift, and discovery over the wired
     *   interface needs no lock.
     */
    fun multicastLock(context: Context, tag: String = "iroh4k"): AutoCloseable {
        val wifi = checkNotNull(
            context.applicationContext.getSystemService(WifiManager::class.java)
        ) {
            "This device has no WifiManager (PackageManager.FEATURE_WIFI is absent), so there is " +
                    "no multicast lock to take; see Iroh4kAndroid.multicastLock."
        }
        val lock = wifi.createMulticastLock(tag)
        // Not reference-counted, deliberately. Reference counting exists for a lock object shared
        // by several call sites, none of which knows whether it is the first to want it or the last
        // to be done with it. Nothing here is shared: every call to multicastLock() creates its own
        // MulticastLock, so the count could never exceed one, and what reference counting would add
        // is a second thing to get right — release() past zero throws
        // RuntimeException("MulticastLock under-locked"), so an idempotent close() would have to
        // keep the count honest rather than merely release once. Turning it off makes acquire and
        // release a plain pair and leaves the whole balancing question to the flag below.
        lock.setReferenceCounted(false)
        lock.acquire()
        return MulticastLockHandle(lock)
    }
}

/**
 * The handle returned by [Iroh4kAndroid.multicastLock].
 *
 * Private on purpose: the type a caller sees is [AutoCloseable], because holding the lock is the
 * whole of the contract and `WifiManager.MulticastLock` is not something this library wants to hand
 * out — a caller with the raw lock could release it out from under the handle.
 */
private class MulticastLockHandle(
    private val lock: WifiManager.MulticastLock,
) : AutoCloseable {

    private val released = AtomicBoolean(false)

    /**
     * Releases the lock, exactly once.
     *
     * `close()` has to be idempotent: `use { }` calls it, and so does a caller who already closed
     * explicitly inside the block. A non-reference-counted `MulticastLock` does happen to ignore a
     * second `release()` — its `mHeld` is false by then — but that is an unstated detail of
     * `WifiManager`'s implementation, and resting this handle's contract on it would be resting it
     * on nothing. The compare-and-set states the property here instead: the release happens once,
     * from whichever thread arrives first, and this handle can never release something it did not
     * acquire.
     */
    override fun close() {
        if (released.compareAndSet(false, true)) lock.release()
    }

    override fun toString(): String = "MulticastLockHandle(held=${!released.get()})"
}

/**
 * The native half of [Iroh4kAndroid.install].
 *
 * Symbol name is part of the ABI: it must match `Java_tech_annexflow_iroh4k_Iroh4kAndroidJni_*`
 * in the Rust `android.rs`. Loading the library first is this object's own job, as it is for
 * [Iroh4kJni] — [install][Iroh4kAndroid.install] is by design the first iroh4k call an app makes,
 * so nothing else has had a chance to load it.
 */
internal object Iroh4kAndroidJni {
    init {
        ensureNativeLoaded()
    }

    external fun installContext(context: Context): Boolean
}
