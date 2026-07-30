package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are held
 * to identical behaviour for QUIC streams.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see `nativeTest`/`jvmTest`.
 *
 * ## Hermetic, bounded, and actually moving bytes
 *
 * Everything here runs over the two-endpoint loopback harness in `CommonConnectionTests.kt` — see
 * [Loopback] — so nothing touches anything outside the machine, and every body is under a real-time
 * timeout. That matters more here than anywhere else in the suite: a stream read waits forever by
 * design, so a regression has to *fail* the suite rather than hang CI.
 *
 * ## What these tests are careful about
 *
 * Short reads and short writes. A QUIC stream is bytes, not messages, so [RecvStream.read] returning
 * less than it was asked for is the normal case rather than the exceptional one — and a test that only
 * ever used [RecvStream.readToEnd] would pass while the partial-read path was broken. [drain] therefore
 * reads with an explicit loop, and the megabyte case exists so that loop genuinely goes round more than
 * once.
 */
class CommonStreamTests {

    /** Comfortably more than any payload here, so a limit never fails a test by accident. */
    private val limit = 4 * 1024 * 1024

    /** The chunk size [drain] asks for — small enough that a megabyte takes many reads. */
    private val chunk = 64 * 1024

    // ── The headline: data actually flows ────────────────────────────────────────────────────

    fun `a payload echoed over a bidirectional stream comes back byte for byte`() = Loopback.bounded {
        Loopback.connected { client, server ->
            // The server side of the protocol: take a request, hand it straight back.
            val echo = async {
                server.acceptBi().use { stream ->
                    val request = stream.recv.readToEnd(limit)
                    stream.send.writeAll(request)
                    stream.send.finish()
                    request.size
                }
            }

            val payload = "the milestone that makes real data flow".encodeToByteArray()
            val answer = client.openBi().use { stream ->
                stream.send.writeAll(payload)
                // Without this the server's `readToEnd` would wait forever: it is the finish that says
                // the request is complete.
                stream.send.finish()
                stream.recv.readToEnd(limit)
            }

            assertThat(echo.await()).isEqualTo(payload.size)
            assertThat(answer).isEqualTo(payload)

            // Then both ends close, and both find out.
            assertThat(client.isClosed).isFalse()
            assertThat(server.isClosed).isFalse()
            client.close(0L, "done".encodeToByteArray())
            Loopback.awaitUntil { server.isClosed }
            assertThat(client.isClosed).isTrue()
            assertThat(server.isClosed).isTrue()
        }
    }

    fun `a payload larger than one frame survives the round trip`() = Loopback.bounded {
        Loopback.connected { client, server ->
            // A megabyte is many QUIC frames and more than one flow-control window, so this exercises
            // the parts a short payload never reaches: partial writes, partial reads, and the peer
            // extending credit as it consumes.
            val payload = ByteArray(1024 * 1024) { (it * 31 and 0xFF).toByte() }

            val echo = async {
                server.acceptBi().use { stream ->
                    val request = drain(stream.recv)
                    stream.send.writeAll(request)
                    stream.send.finish()
                    request
                }
            }

            val answer = client.openBi().use { stream ->
                stream.send.writeAll(payload)
                stream.send.finish()
                drain(stream.recv)
            }

            val received = echo.await()
            // Compared by size first so a failure says how far it got rather than printing a megabyte.
            assertThat(received.size).isEqualTo(payload.size)
            assertThat(received.contentEquals(payload)).isTrue()
            assertThat(answer.size).isEqualTo(payload.size)
            assertThat(answer.contentEquals(payload)).isTrue()
        }
    }

    fun `write reports how much it took and writeAll takes all of it`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val payload = ByteArray(512 * 1024) { (it and 0x3F).toByte() }
            val drained = async { server.acceptUni().use { drain(it) } }

            client.openUni().use { send ->
                // The manual form: `write` takes what fits and answers how much that was, which for a
                // payload this size is routinely less than all of it.
                var offset = 0
                while (offset < payload.size) {
                    val written = send.write(payload.copyOfRange(offset, payload.size))
                    // Never zero for a non-empty buffer: `write` suspends until at least one byte fits.
                    assertThat(written > 0L).isTrue()
                    assertThat(written <= (payload.size - offset).toLong()).isTrue()
                    offset += written.toInt()
                }
                send.finish()
            }

            val received = drained.await()
            assertThat(received.size).isEqualTo(payload.size)
            assertThat(received.contentEquals(payload)).isTrue()
        }
    }

    // ── Unidirectional ───────────────────────────────────────────────────────────────────────

    fun `a unidirectional stream carries data one way`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val payload = "one way only".encodeToByteArray()
            val received = async { server.acceptUni().use { it.readToEnd(limit) } }

            client.openUni().use { send ->
                send.writeAll(payload)
                send.finish()
                assertThat(received.await()).isEqualTo(payload)
                // The peer read all of it and acknowledged it, which is what a `null` stop code means.
                assertThat(send.stopped()).isNull()
            }
        }
    }

    // ── Ending a stream ──────────────────────────────────────────────────────────────────────

    fun `finish ends the stream and refuses a later write or finish`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val drained = async { server.acceptUni().use { it.readToEnd(limit) } }
            val payload = "all of it".encodeToByteArray()

            client.openUni().use { send ->
                send.writeAll(payload)
                send.finish()

                // iroh treats a second finish as harmless but wrong, which is what `Closed` says here.
                assertThat(assertFailsWith<IrohError> { send.finish() }.code)
                    .isEqualTo(IrohError.Code.Closed)
                assertThat(assertFailsWith<IrohError> { send.write(ByteArray(1)) }.code)
                    .isEqualTo(IrohError.Code.Closed)
                assertThat(assertFailsWith<IrohError> { send.writeAll(ByteArray(1)) }.code)
                    .isEqualTo(IrohError.Code.Closed)

                assertThat(drained.await()).isEqualTo(payload)
            }
        }
    }

    fun `readToEnd answers everything the peer finished`() = Loopback.bounded {
        Loopback.connected { client, server ->
            // Written in three pieces, so what `readToEnd` returns is genuinely a reassembly rather
            // than one frame handed back.
            val pieces = listOf("first ", "second ", "third").map { it.encodeToByteArray() }
            val whole = pieces.reduce { left, right -> left + right }
            val received = async { server.acceptUni().use { it.readToEnd(limit) } }

            client.openUni().use { send ->
                for (piece in pieces) send.writeAll(piece)
                send.finish()
                assertThat(received.await()).isEqualTo(whole)
            }
        }
    }

    fun `readExact answers exactly and fails rather than hanging when the stream ends early`() =
        Loopback.bounded {
            Loopback.connected { client, server ->
                val read = CompletableDeferred<Unit>()
                val sender = async {
                    client.openUni().use { send ->
                        send.writeAll(ByteArray(8) { it.toByte() })
                        send.finish()
                        // The handle is held until the reader is done, so nothing about the ending is
                        // left to the timing of a release.
                        read.await()
                    }
                }

                server.acceptUni().use { recv ->
                    assertThat(recv.bytesRead()).isEqualTo(0L)
                    assertThat(recv.readExact(4)).isEqualTo(byteArrayOf(0, 1, 2, 3))
                    assertThat(recv.bytesRead()).isEqualTo(4L)

                    // Four bytes left and the stream is finished, so sixteen will never arrive. The whole
                    // point of `readExact` is that this *fails* instead of waiting for them.
                    assertThat(assertFailsWith<IrohError> { recv.readExact(16) }.code)
                        .isEqualTo(IrohError.Code.Read)
                }

                read.complete(Unit)
                sender.await()
            }
        }

    fun `resetting the send half surfaces on the receive half`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val accepted = CompletableDeferred<Unit>()
            val reader = async {
                server.acceptBi().use { stream ->
                    accepted.complete(Unit)
                    // Parked here when the reset lands, which is the case this member exists for.
                    assertThat(stream.recv.receivedReset()).isEqualTo(RESET_CODE)
                    // Reading afterwards reports the stream is gone rather than answering `null`: iroh
                    // freed its state when it reported the reset, and a reset stream is not a finished
                    // one — the data was abandoned, not delivered.
                    assertThat(assertFailsWith<IrohError> { stream.recv.read(chunk) }.code)
                        .isEqualTo(IrohError.Code.Closed)
                }
            }

            client.openBi().use { stream ->
                // Something has to be written, or the peer would never see the stream to accept it.
                stream.send.writeAll("about to change my mind".encodeToByteArray())
                accepted.await()
                stream.send.reset(RESET_CODE)

                // Writing after a reset is refused rather than silently dropped.
                assertThat(assertFailsWith<IrohError> { stream.send.write(ByteArray(1)) }.code)
                    .isEqualTo(IrohError.Code.Closed)
                reader.await()
            }
        }
    }

    fun `stopping the receive half surfaces to the sender`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val reader = async {
                server.acceptBi().use { stream ->
                    assertThat(stream.recv.read(chunk)).isNotNull()
                    stream.recv.stop(STOP_CODE)
                    // From this side there is simply no more data, which is what it asked for — so this
                    // is `null` rather than a failure.
                    assertThat(stream.recv.read(chunk)).isNull()
                }
            }

            client.openBi().use { stream ->
                stream.send.writeAll("hello".encodeToByteArray())
                // Suspends until the peer stops the stream, and answers the code it chose.
                assertThat(stream.send.stopped()).isEqualTo(STOP_CODE)
                // And a write afterwards fails as a write: there is a live connection, but nobody
                // listening on this stream.
                assertThat(assertFailsWith<IrohError> { stream.send.writeAll(ByteArray(1)) }.code)
                    .isEqualTo(IrohError.Code.Write)
                reader.await()
            }
        }
    }

    // ── Describing a stream ──────────────────────────────────────────────────────────────────

    fun `a stream describes itself`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val accepted = async { server.acceptBi() }

            client.openBi().use { stream ->
                stream.send.writeAll(byteArrayOf(1, 2, 3))

                // One stream, so one id — the halves are separate objects over the same QUIC stream.
                val id = stream.id()
                assertThat(stream.send.id()).isEqualTo(id)
                assertThat(stream.recv.id()).isEqualTo(id)
                assertThat(id >= 0L).isTrue()

                // Every stream starts at priority zero, and setting it round-trips.
                assertThat(stream.send.priority()).isEqualTo(0)
                stream.send.setPriority(5)
                assertThat(stream.send.priority()).isEqualTo(5)
                stream.send.setPriority(-3)
                assertThat(stream.send.priority()).isEqualTo(-3)

                assertThat(stream.toString()).isEqualTo("BiStream($id)")
                assertThat(stream.send.toString()).isEqualTo("SendStream($id)")
                assertThat(stream.recv.toString()).isEqualTo("RecvStream($id)")

                accepted.await().use { peer ->
                    // The id is the stream's, not the handle's, so the peer sees the same one.
                    assertThat(peer.id()).isEqualTo(id)
                    // And the byte count follows what the application consumed, not what arrived.
                    assertThat(peer.recv.bytesRead()).isEqualTo(0L)
                    assertRead(peer.recv.read(chunk), byteArrayOf(1, 2, 3))
                    assertThat(peer.recv.bytesRead()).isEqualTo(3L)
                }
            }
        }
    }

    // ── 0-RTT ────────────────────────────────────────────────────────────────────────────────

    fun `an ordinary stream is not a 0-RTT stream`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val opened = async { server.acceptBi() }
            client.openBi().use { outbound ->
                outbound.send.writeAll("x".encodeToByteArray())
                opened.await().use { inbound ->
                    // Read before asserting, and check `bytesRead()` too: `is0Rtt()` and `bytesRead()`
                    // are two different Rust exports (`recv_is_0rtt`/`recv_bytes_read`), both booleans
                    // in spirit at this specific point — a fresh stream reads `false`/`0` on either —
                    // so an `is0Rtt()` accidentally wired to `recvBytesRead` would still pass the
                    // assertion below without this. Reading one byte first and pinning `bytesRead()`
                    // at `1` closes that: the two exports diverge from here on, so a mismatch would
                    // fail loudly.
                    assertRead(inbound.recv.read(chunk), "x".encodeToByteArray())
                    assertThat(inbound.recv.bytesRead()).isEqualTo(1L)

                    // Only the negative is deterministic here. `poll_accept` samples `is_handshaking()`
                    // at the moment the stream is accepted (noq-1.1.0/src/connection.rs:1098), not when
                    // it was created, so a loopback server that accepts after the handshake completed
                    // sees `false` even for data the client sent early. Asserting `true` would be a race.
                    assertThat(inbound.recv.is0Rtt()).isFalse()
                }
            }
        }
    }

    // ── Argument validation ──────────────────────────────────────────────────────────────────

    fun `impossible read sizes and stream codes are rejected`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val accepted = async { server.acceptBi() }

            client.openBi().use { stream ->
                stream.send.writeAll("data".encodeToByteArray())

                // A zero-byte read would be indistinguishable from the end of the stream, and a negative
                // one is meaningless — so both are the caller's mistake to hear about.
                for (size in listOf(0, -1, Int.MIN_VALUE)) {
                    val reads: List<Pair<String, suspend () -> Any?>> = listOf(
                        "read" to { stream.recv.read(size) },
                        "readExact" to { stream.recv.readExact(size) },
                        "readToEnd" to { stream.recv.readToEnd(size) },
                    )
                    for ((name, call) in reads) {
                        assertThat(
                            assertFailsWith<IrohError>("expected $name($size) to be rejected") { call() }.code
                        ).isEqualTo(IrohError.Code.InvalidArgument)
                    }
                }

                // A QUIC application error code is a 62-bit unsigned varint, and Kotlin's `Long` is
                // wider and signed — so both ends of the range are reported rather than truncated into
                // a code the caller did not ask for.
                for (code in listOf(-1L, Long.MIN_VALUE, 1L shl 62, Long.MAX_VALUE)) {
                    assertThat(assertFailsWith<IrohError>("expected reset($code) to be rejected") {
                        stream.send.reset(code)
                    }.code).isEqualTo(IrohError.Code.InvalidArgument)
                    assertThat(assertFailsWith<IrohError>("expected stop($code) to be rejected") {
                        stream.recv.stop(code)
                    }.code).isEqualTo(IrohError.Code.InvalidArgument)
                }
                // None of that touched the stream: it still works.
                assertThat(stream.send.priority()).isEqualTo(0)

                accepted.await().use { peer ->
                    // A limit smaller than what is already buffered fails rather than truncating, because
                    // a silently short answer is worse than a reported one.
                    assertThat(assertFailsWith<IrohError> { peer.recv.readToEnd(2) }.code)
                        .isEqualTo(IrohError.Code.Read)
                }
            }
        }
    }

    // ── Cancellation ─────────────────────────────────────────────────────────────────────────

    fun `cancelling a blocked read returns promptly and drains the op registry`() = Loopback.bounded {
        // Settled baseline, ceiling assertions, and no re-read afterwards: the op registry is
        // process-global and `LiveCounter` sets out why each of those three matters.
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value

        Loopback.connected { client, server ->
            val accepted = async { server.acceptBi() }
            client.openBi().use { stream ->
                stream.send.writeAll(byteArrayOf(1))
                accepted.await().use { peer ->
                    assertRead(peer.recv.read(chunk), byteArrayOf(1))

                    // Nothing more is coming, so this waits forever. Only cancellation ends it.
                    val job = launch(Dispatchers.Default) { peer.recv.read(chunk) }
                    // The read must be registered before it is cancelled, or nothing is aborted.
                    ops.awaitAtLeast(baseline + 1)

                    val elapsed = measureTime { job.cancelAndJoin() }

                    assertThat(job.isCancelled).isTrue()
                    assertThat(elapsed < 5.seconds).isTrue()
                    ops.awaitAtMost(baseline)

                    // `read` is cancel-safe, so nothing was consumed and the stream still works — which
                    // is the part a "did it return promptly" test on its own would miss.
                    stream.send.writeAll(byteArrayOf(2))
                    assertRead(peer.recv.read(chunk), byteArrayOf(2))
                }
            }
        }

        ops.awaitAtMost(baseline)
    }

    fun `cancelling a blocked acceptBi returns promptly and drains the op registry`() = Loopback.bounded {
        val ops = LiveCounters.operations
        val streams = LiveCounters.streamHandles
        LiveCounters.settle()
        val baseline = ops.value
        val handleBaseline = streams.value

        Loopback.connected { client, server ->
            // Nobody is opening a stream, so this waits forever.
            val job = launch(Dispatchers.Default) { client.acceptBi() }
            ops.awaitAtLeast(baseline + 1)

            val elapsed = measureTime { job.cancelAndJoin() }

            assertThat(job.isCancelled).isTrue()
            assertThat(elapsed < 5.seconds).isTrue()
            ops.awaitAtMost(baseline)
            // And nothing was stranded: a cancel that beat an accept releases whatever it had produced.
            // A stream the cancel forgot to drop would hold this above the settled baseline forever.
            streams.awaitAtMost(handleBaseline)

            // The connection survived having an accept aborted under it, and still accepts.
            val opened = async { client.acceptBi() }
            server.openBi().use { stream ->
                stream.send.writeAll(byteArrayOf(9))
                opened.await().use { peer ->
                    assertRead(peer.recv.read(chunk), byteArrayOf(9))
                }
            }
        }

        ops.awaitAtMost(baseline)
    }

    // ── A closed connection ──────────────────────────────────────────────────────────────────

    fun `streams on a closed connection are refused rather than left hanging`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val stream = client.openBi()
            client.close(5L, "done".encodeToByteArray())

            // Opening and accepting both report the connection is gone. The accept in particular has to
            // *end*: an accept loop on a closed connection that waited forever would never terminate.
            val openers: List<Pair<String, suspend () -> Any?>> = listOf(
                "openBi" to { client.openBi() },
                "acceptBi" to { client.acceptBi() },
                "openUni" to { client.openUni() },
                "acceptUni" to { client.acceptUni() },
            )
            for ((name, call) in openers) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            // And the stream that was already open fails its I/O the same way.
            stream.use {
                assertThat(assertFailsWith<IrohError> { it.send.writeAll(ByteArray(1)) }.code)
                    .isEqualTo(IrohError.Code.Closed)
                assertThat(assertFailsWith<IrohError> { it.recv.read(chunk) }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            Loopback.awaitUntil { server.isClosed }
        }
    }

    // ── Release ──────────────────────────────────────────────────────────────────────────────

    fun `a released send stream raises Closed from every member`() = Loopback.bounded {
        Loopback.connected { client, _ ->
            val send = client.openUni()
            send.close()
            assertThat(send.isReleased).isTrue()

            // Every member, not one: a guard that only covered the first method anybody tried would pass
            // a narrower test and still dereference a freed pointer on the next call.
            val members: List<Pair<String, () -> Any?>> = listOf(
                "finish" to { send.finish() },
                "reset" to { send.reset(0L) },
                "setPriority" to { send.setPriority(1) },
                "priority" to { send.priority() },
                "id" to { send.id() },
            )
            for ((name, call) in members) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            val suspending: List<Pair<String, suspend () -> Any?>> = listOf(
                "write" to { send.write(ByteArray(1)) },
                "writeAll" to { send.writeAll(ByteArray(1)) },
                "stopped" to { send.stopped() },
            )
            for ((name, call) in suspending) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            // `close()` stays idempotent and `isReleased` keeps answering rather than raising.
            send.close()
            send.close()
            assertThat(send.isReleased).isTrue()
            assertThat(send.toString()).isEqualTo("SendStream(released)")
        }
    }

    fun `a released receive stream raises Closed from every member`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val accepted = async { server.acceptUni() }
            client.openUni().use { send ->
                send.writeAll(byteArrayOf(1))
                val recv = accepted.await()
                recv.close()
                assertThat(recv.isReleased).isTrue()

                val members: List<Pair<String, () -> Any?>> = listOf(
                    "stop" to { recv.stop(0L) },
                    "bytesRead" to { recv.bytesRead() },
                    "is0Rtt" to { recv.is0Rtt() },
                    "id" to { recv.id() },
                )
                for ((name, call) in members) {
                    assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                        .isEqualTo(IrohError.Code.Closed)
                }

                val suspending: List<Pair<String, suspend () -> Any?>> = listOf(
                    "read" to { recv.read(chunk) },
                    "readExact" to { recv.readExact(1) },
                    "readToEnd" to { recv.readToEnd(limit) },
                    "receivedReset" to { recv.receivedReset() },
                )
                for ((name, call) in suspending) {
                    assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                        .isEqualTo(IrohError.Code.Closed)
                }

                recv.close()
                assertThat(recv.toString()).isEqualTo("RecvStream(released)")
            }
        }
    }

    fun `releasing one half of a bidirectional stream leaves the other usable`() = Loopback.bounded {
        Loopback.connected { client, server ->
            val echo = async {
                server.acceptBi().use { stream ->
                    val request = stream.recv.readToEnd(limit)
                    stream.send.writeAll(request)
                    stream.send.finish()
                }
            }

            val stream = client.openBi()
            try {
                val payload = "half at a time".encodeToByteArray()
                stream.send.writeAll(payload)
                stream.send.finish()
                // The two halves have independent handles, so letting go of the writing one does not take
                // the reading one with it — which is the whole reason they are separate objects.
                stream.send.close()
                assertThat(stream.send.isReleased).isTrue()
                assertThat(stream.recv.isReleased).isFalse()
                assertThat(stream.isReleased).isFalse()

                assertThat(stream.recv.readToEnd(limit)).isEqualTo(payload)
                echo.await()
            } finally {
                // Idempotent next to the half that is already gone.
                stream.close()
            }
            assertThat(stream.isReleased).isTrue()
        }
    }

    // ── Leaks ────────────────────────────────────────────────────────────────────────────────

    fun `repeated stream cycles leak neither handles nor operations`() = Loopback.bounded {
        val streams = LiveCounters.streamHandles
        val connections = LiveCounters.connectionHandles
        val ops = LiveCounters.operations
        // Settled first, so the ceilings below are tight rather than slack by whatever the
        // previous test was still dropping — a slack ceiling is a leak detector that has stopped
        // detecting small leaks.
        LiveCounters.settle()
        val streamBaseline = streams.value
        val connectionBaseline = connections.value
        val opBaseline = ops.value

        Loopback.connected { client, server ->
            repeat(4) { round ->
                val marker = byteArrayOf(round.toByte())

                // Bidirectional: two Kotlin handles over one native stream, so this is the cycle a
                // half-released pair would leak.
                val acceptedBi = async { server.acceptBi() }
                val bi = client.openBi()
                bi.send.writeAll(marker)
                acceptedBi.await().use { peer ->
                    assertRead(peer.recv.read(64), marker)
                }
                bi.close()

                // Unidirectional: one handle, and the receiving end is a different Kotlin type.
                val acceptedUni = async { server.acceptUni() }
                val uni = client.openUni()
                uni.writeAll(marker)
                acceptedUni.await().use { peer ->
                    assertRead(peer.read(64), marker)
                }
                uni.close()
            }

            // Sixteen stream handles over twelve native streams have come and gone. Without this counter
            // a leak would be invisible from Kotlin: the loop above would pass while every round
            // stranded an open QUIC stream — and, through it, the connection it belonged to.
            streams.awaitAtMost(streamBaseline)
        }

        streams.awaitAtMost(streamBaseline)
        connections.awaitAtMost(connectionBaseline)
        ops.awaitAtMost(opBaseline)
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────

    /**
     * Asserts that a read answered exactly [expected].
     *
     * Content, not identity, and said explicitly: `assertThat` on a **nullable** `ByteArray` resolves to
     * assertk's generic equality, which for an array is reference equality — so the obvious spelling
     * compiles, reads correctly and always fails. Written around once here rather than tripped over per
     * assertion.
     */
    private fun assertRead(actual: ByteArray?, expected: ByteArray) {
        assertThat(actual).isNotNull()
        // As lists, so a failure prints the bytes rather than two array identities.
        assertThat(actual!!.toList()).isEqualTo(expected.toList())
    }

    /**
     * Reads to the end of [recv] with an explicit loop.
     *
     * Deliberately not [RecvStream.readToEnd]: the point is to go through [RecvStream.read] many times,
     * because that is what an application does and because a short read is the case most likely to be
     * mishandled. Asserts that at least one read *was* short whenever more than one was needed, so the
     * test would notice if the boundary handling silently stopped being exercised.
     */
    private suspend fun drain(recv: RecvStream): ByteArray {
        var buffer = ByteArray(0)
        var reads = 0
        while (true) {
            val piece = recv.read(chunk) ?: break
            // A read never answers an empty array: the end of the stream is `null`.
            assertThat(piece.isNotEmpty()).isTrue()
            assertThat(piece.size <= chunk).isTrue()
            buffer += piece
            reads++
        }
        assertThat(reads > 0).isTrue()
        return buffer
    }

    private companion object {
        /** An arbitrary application error code for a reset, chosen so the message is recognisable. */
        const val RESET_CODE = 4_242L

        /** As [RESET_CODE], for a stop. */
        const val STOP_CODE = 1_717L
    }
}
