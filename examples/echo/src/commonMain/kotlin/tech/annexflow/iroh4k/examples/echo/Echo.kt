package tech.annexflow.iroh4k.examples.echo

import kotlinx.coroutines.runBlocking
import tech.annexflow.iroh4k.Connection
import tech.annexflow.iroh4k.Endpoint
import tech.annexflow.iroh4k.EndpointConfig
import tech.annexflow.iroh4k.EndpointPreset
import tech.annexflow.iroh4k.Iroh4k
import tech.annexflow.iroh4k.RelayMode
import tech.annexflow.iroh4k.Router
import tech.annexflow.iroh4k.SecretKey
import tech.annexflow.iroh4k.SocketAddr
import tech.annexflow.iroh4k.acceptBi
import tech.annexflow.iroh4k.connect
import tech.annexflow.iroh4k.openBi

/**
 * Two iroh endpoints in one process: one serves an echo protocol behind a [Router], the other dials
 * it by public key, opens a bidirectional stream, sends a payload and reads it back.
 *
 * Run it with `./gradlew :examples:echo:jvmRun`, or build the native binary with
 * `./gradlew :examples:echo:linkReleaseExecutableMacosArm64` and run the `.kexe` it produces under
 * `build/bin/`.
 *
 * ## Hermetic on purpose
 *
 * Both endpoints use [EndpointPreset.Minimal] with [RelayMode.Disabled], bound to `127.0.0.1:0`.
 * That combination is the one thing in this file worth copying verbatim for a test: `Minimal`
 * installs a TLS provider and nothing else — no relays, no DNS or pkarr address lookup — and an
 * explicit `bindAddrs` *replaces* iroh's default sockets rather than adding to them. So nothing here
 * leaves the machine, the example runs on a plane, and it cannot be flaky because of somebody else's
 * network.
 *
 * The cost is that the dialler has to be told where the server is, since there is no discovery to do
 * it: that is what passing `server.addr()` to [connect] is for. Over a real network with
 * [EndpointPreset.N0] the dialler needs only the [tech.annexflow.iroh4k.EndpointId], and iroh finds
 * the rest.
 *
 * ## And a lesson in endings
 *
 * An example is documentation of lifecycle as much as of API, so every handle here is released, in
 * order, and the graceful ending is used rather than the abrupt one:
 *
 *  - `use { }` on everything that owns a native handle — endpoints, connections, streams.
 *  - [Router.shutdown] before the endpoint it accepts on goes away, because the router does not own
 *    that endpoint and will not close it.
 *  - [Connection.close] with a code and a reason before the handle is released, and
 *    [Endpoint.shutdown] before [Endpoint.close], so the peer learns *why* rather than discovering it
 *    from a timeout.
 */

/** The protocol both sides negotiate. An ALPN is opaque octets in TLS, hence the bytes. */
private val ECHO_ALPN = "iroh4k/echo/0".encodeToByteArray()

/** Nothing this example exchanges is close to this; it is here because an unbounded read is a bug. */
private const val MAX_MESSAGE = 64 * 1024

/** The QUIC application error code both sides close with when everything went fine. */
private const val OK: Long = 0

fun main() = runBlocking {
    println("iroh4k ${Iroh4k.version}")

    // Generated rather than hard-coded, so each run has a new identity. A real application saves
    // these bytes (`SecretKey.toBytes()`) and passes them back on the next start, which is what
    // makes an endpoint keep its id — and therefore its address — across restarts.
    val serverKey = SecretKey.generate()
    val clientKey = SecretKey.generate()

    Endpoint.bind(loopbackConfig(serverKey)).use { server ->
        Endpoint.bind(loopbackConfig(clientKey)).use { client ->
            println("server  id ${server.id}")
            println("server  bound to ${server.boundSockets().joinToString()}")
            println("client  id ${client.id}")

            // `spawn` sets the endpoint's ALPN list to exactly the registered protocols, which is why
            // `loopbackConfig` does not declare any: registering the handler is what makes the
            // server answer `iroh4k/echo/0`, and nothing else.
            val router = Router.builder(server)
                .accept(ECHO_ALPN) { connection -> echo(connection) }
                // The default sink is silence, as iroh's own `warn!` is until a subscriber exists.
                // A router survives a failed handshake, an unroutable ALPN and a throwing handler,
                // so an example that printed nothing here would be hiding the only place those can
                // be seen.
                .onFailure { alpn, failure ->
                    println("server  router failure on ${alpn?.decodeToString() ?: "no ALPN"}: $failure")
                }
                .spawn()

            try {
                // The whole address, not just the id: with discovery switched off this is the only
                // way the dialler can find the server. Printing it shows what iroh actually knows
                // about itself moments after binding — the loopback socket, and no relay.
                val addr = server.addr()
                println("client  dialling $addr")

                client.connect(addr, ECHO_ALPN).use { connection ->
                    println(
                        "client  connected to ${connection.remoteId().fmtShort()}" +
                                " over ALPN '${connection.alpn().decodeToString()}'" +
                                " as ${connection.side()}"
                    )

                    val request = "hello from ${client.id.fmtShort()}".encodeToByteArray()
                    connection.openBi().use { stream ->
                        println("client  stream ${stream.id()} sending '${request.decodeToString()}'")
                        stream.send.writeAll(request)
                        // Without this the server's `readToEnd` never returns: it is `finish` that
                        // tells the peer the request is complete.
                        stream.send.finish()

                        val echoed = stream.recv.readToEnd(MAX_MESSAGE)
                        println("client  received '${echoed.decodeToString()}'")
                        check(echoed.contentEquals(request)) { "the echo did not match the request" }
                    }

                    // Says why, before the `use` above releases the handle. A plain release closes
                    // with code 0 and an empty reason, which the peer cannot tell apart from a
                    // crash.
                    connection.close(OK, "done".encodeToByteArray())
                }

                println("client  round trip complete")
            } finally {
                // Before the endpoint: the router accepts on `server`, does not own it, and will not
                // close it. `shutdown` waits for the handlers, unlike `close`.
                router.shutdown()
            }

            // iroh's own async `close()`: tells peers the endpoint is going away and waits for them
            // to acknowledge it. The `use { }` blocks then release the handles.
            client.shutdown()
            server.shutdown()
            println("both endpoints shut down")
        }
    }
}

/**
 * Serves one echo connection: read a request off a bidirectional stream, write it straight back.
 *
 * The router lends this connection for exactly as long as this function runs and releases it the
 * moment it returns, so all the work happens here and nothing is retained past the end.
 */
private suspend fun echo(connection: Connection) {
    println("server  serving ${connection.remoteId().fmtShort()} over '${connection.alpn().decodeToString()}'")

    connection.acceptBi().use { stream ->
        val request = stream.recv.readToEnd(MAX_MESSAGE)
        println("server  stream ${stream.id()} echoing ${request.size} bytes")
        stream.send.writeAll(request)
        stream.send.finish()

        // `writeAll` returns once QUIC has taken the bytes, not once the peer has them — and the
        // router releases this connection the instant this handler returns, which closes it. So
        // without this wait the last packet of the echo could be dropped by our own shutdown.
        // `stopped()` answers null when the peer acknowledged everything, or its stop code when it
        // decided it had read enough; both mean the data is no longer ours to deliver.
        stream.send.stopped()
    }
}

/**
 * An endpoint that talks to loopback and nothing else — see the file header.
 *
 * No ALPNs: the server's are set by [Router.builder]`.spawn()`, and the client names its protocol on
 * the [connect] call.
 */
private fun loopbackConfig(secretKey: SecretKey) = EndpointConfig(
    preset = EndpointPreset.Minimal,
    secretKey = secretKey,
    relayMode = RelayMode.Disabled,
    bindAddrs = listOf(SocketAddr.parse("127.0.0.1:0")),
)
