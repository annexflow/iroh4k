package tech.annexflow.iroh4k

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * What only a device can prove.
 *
 * Every other suite in this repository loads the library from a path the build handed it: the JVM
 * and Robolectric tests both go through `iroh4k.native.path`, which is the branch a consumer never
 * takes. Here the `.so` was cross-compiled by `cargo ndk`, packaged into the APK, unpacked by the
 * platform installer for this device's ABI, and resolved by `System.loadLibrary` — and the code
 * calling into it runs on ART, not HotSpot, over a real Android kernel's UDP sockets.
 *
 * The shared `Common*Tests` bodies deliberately do not run here; the comment on
 * `withDeviceTestBuilder` in `iroh4k/build.gradle.kts` says why (their backtick names become class
 * names with spaces, which DEX rejects below version 040). The host tests cover those bodies. What
 * is left for this file is the part that is genuinely different on a device, taken one layer at a
 * time: the loader, a synchronous JNI call, the async bridge, the Rust-owned handles, real
 * sockets, and finally a whole QUIC round trip.
 *
 * Method names avoid backticks for the same DEX reason.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTests {

    /**
     * The loader took the `System.loadLibrary` branch, not the test-only one.
     *
     * `androidMain`'s loader tries `iroh4k.native.path` first and falls back to `loadLibrary`; on a
     * device nothing sets that property, so its absence is what makes the rest of this file a test
     * of the packaged `.so`. Asserting it here means that if the property ever leaks into an
     * instrumented run, these tests say so instead of quietly proving the same thing the host
     * tests already do.
     */
    @Test
    fun libraryCameFromTheApkAndNotFromAPath() {
        assertNull(System.getProperty("iroh4k.native.path"))
        // Forces the loader if nothing else has yet: `version` is the first JNI call made.
        assertTrue(Iroh4k.version.isNotEmpty())
    }

    /** A synchronous JNI call, returning a `ByteArray` allocated by Rust and decoded by Kotlin. */
    @Test
    fun versionReportsBothHalves() {
        val version = Iroh4k.version
        assertTrue(version.startsWith("0.1.0+iroh"), "unexpected version string: $version")
    }

    /**
     * Ed25519 through the JNI facade on ART: generate, derive, sign, verify, and the negative
     * case. Pure computation in Rust, so a failure here is the boundary rather than the network.
     */
    @Test
    fun keysSignAndVerifyOnDevice() {
        val secret = SecretKey.generate()
        val id = secret.public()

        assertEquals(id, SecretKey.fromBytes(secret.toBytes()).public())
        assertNotEquals(secret, SecretKey.generate())

        val message = "iroh4k on android".encodeToByteArray()
        val signature = secret.sign(message)
        assertTrue(id.verify(message, signature))
        assertFalse(id.verify("iroh4k on androix".encodeToByteArray(), signature))
    }

    /**
     * The async bridge: a `suspend` call that parks on `opAwait` in a `Dispatchers.IO` thread and
     * is resumed when the tokio task completes. `Endpoint.bind` is the shortest public operation
     * that goes through it, and binding also proves the device let iroh open a UDP socket.
     */
    @Test
    fun endpointBindsARealSocketAndKeepsItsIdentity() = runBlocking {
        val secret = SecretKey.generate()
        Endpoint.bind(loopbackConfig(secret)).use { endpoint ->
            assertEquals(secret.public(), endpoint.id)

            val bound = endpoint.boundSockets()
            assertTrue(bound.isNotEmpty(), "endpoint bound to nothing")
            assertTrue(bound.any { it.port != 0 }, "no real port in $bound")

            endpoint.shutdown()
        }
    }

    /**
     * The whole stack end to end, and the only test here that exercises QUIC itself: two endpoints
     * on this device's loopback, a router serving an echo protocol, a bidirectional stream, and a
     * graceful close on both sides. If the packaged `.so` were subtly wrong — a mismatched ABI, a
     * missing symbol, a codec that disagrees with the JVM's — this is where it would show.
     */
    @Test
    fun twoEndpointsEchoOverQuic() = runBlocking {
        val alpn = "iroh4k/device-test/0".encodeToByteArray()
        val request = "hello from an emulator".encodeToByteArray()

        Endpoint.bind(loopbackConfig(SecretKey.generate())).use { server ->
            Endpoint.bind(loopbackConfig(SecretKey.generate())).use { client ->
                val router = Router.builder(server)
                    .accept(alpn) { connection ->
                        connection.acceptBi().use { stream ->
                            val received = stream.recv.readToEnd(MAX_MESSAGE)
                            stream.send.writeAll(received)
                            stream.send.finish()
                            // The router closes the connection as soon as this returns, so wait
                            // for the peer to have the bytes rather than only QUIC.
                            stream.send.stopped()
                        }
                    }
                    .spawn()

                try {
                    client.connect(server.addr(), alpn).use { connection ->
                        assertEquals(server.id, connection.remoteId())

                        connection.openBi().use { stream ->
                            stream.send.writeAll(request)
                            stream.send.finish()
                            val echoed = stream.recv.readToEnd(MAX_MESSAGE)
                            assertTrue(
                                echoed.contentEquals(request),
                                "echo mismatch: ${echoed.decodeToString()}",
                            )
                        }

                        connection.close(0, "done".encodeToByteArray())
                    }
                } finally {
                    router.shutdown()
                }

                client.shutdown()
                server.shutdown()
            }
        }
    }

    private companion object {
        const val MAX_MESSAGE = 64 * 1024

        /**
         * Hermetic by construction, the same way the shared suites are: `Minimal` installs a TLS
         * provider and nothing else — no DNS, no pkarr — and with relays disabled and a loopback
         * bind this endpoint talks to nothing it was not told about. That matters more on a device
         * than on a build machine: an emulator's network is a NAT away from anything real, and a
         * test that needed it would fail for reasons that have nothing to do with this library.
         */
        fun loopbackConfig(secretKey: SecretKey) = EndpointConfig(
            preset = EndpointPreset.Minimal,
            secretKey = secretKey,
            relayMode = RelayMode.Disabled,
            bindAddrs = listOf(SocketAddr.parse("127.0.0.1:0")),
        )
    }
}
