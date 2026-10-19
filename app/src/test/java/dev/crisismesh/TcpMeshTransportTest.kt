package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TcpMeshTransportTest {
    private fun port(): Int = ServerSocket(0).use { it.localPort }
    private val payload = "CM1|5|2|flood|rescue".toByteArray()

    @Test fun successArrivesOnlyAfterReceiveCallbackCompletes() {
        val transport = TcpMeshTransport()
        val received = CompletableFuture<ByteArray>()
        val releaseCommit = java.util.concurrent.CountDownLatch(1)
        val listening = CompletableFuture<Int>()
        try {
            transport.listen(0, { received.complete(it); check(releaseCommit.await(5, TimeUnit.SECONDS)) },
                { if (it.startsWith("Nasłuch")) listening.complete(it.substringAfterLast(' ').toInt()) })
            val result = CompletableFuture<Result<Unit>>()
            transport.send("127.0.0.1:${listening.get(5, TimeUnit.SECONDS)}", payload) { result.complete(it) }
            assertArrayEquals(payload, received.get(5, TimeUnit.SECONDS))
            assertFalse("Must not confirm before receive transaction returns", result.isDone)
            releaseCommit.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS).isSuccess)
        } finally { releaseCommit.countDown(); transport.close() }
    }

    @Test fun storageFailureCannotProduceSuccessReceipt() {
        val transport = TcpMeshTransport()
        val listening = CompletableFuture<Int>()
        try {
            transport.listen(0, { throw java.io.IOException("commit failed") },
                { if (it.startsWith("Nasłuch")) listening.complete(it.substringAfterLast(' ').toInt()) })
            val result = CompletableFuture<Result<Unit>>()
            transport.send("127.0.0.1:${listening.get(5, TimeUnit.SECONDS)}", payload) { result.complete(it) }
            assertTrue(result.get(5, TimeUnit.SECONDS).isFailure)
        } finally { transport.close() }
    }

    @Test fun legacyFrameOnlyAckIsNotPersistenceConfirmation() {
        ServerSocket(0).use { server ->
            val receiver = Thread {
                server.accept().use { socket ->
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val bytes = ByteArray(input.readInt()); input.readFully(bytes)
                    socket.getOutputStream().write(6) // CMF1 frame-only receipt, deliberately incompatible.
                    socket.getOutputStream().flush()
                    input.read()
                }
            }.apply { isDaemon = true; start() }
            val transport = TcpMeshTransport()
            try {
                val result = CompletableFuture<Result<Unit>>()
                transport.send("127.0.0.1:${server.localPort}", payload) { result.complete(it) }
                assertTrue(result.get(5, TimeUnit.SECONDS).isFailure)
            } finally { transport.close(); receiver.join(1000) }
        }
    }

    @Test fun invalidAddressAndUnreachableNeighborRemainFailures() {
        val transport = TcpMeshTransport()
        try {
            for (address in listOf("not-an-address", "127.0.0.1:${port()}")) {
                val result = CompletableFuture<Result<Unit>>()
                transport.send(address, payload) { result.complete(it) }
                assertTrue(result.get(5, TimeUnit.SECONDS).isFailure)
            }
        } finally { transport.close() }
    }

    @Test fun listenerRestartsWithoutOldAcceptLoopReceivingNewPackets() {
        val transport = TcpMeshTransport()
        val samePort = port()
        try {
            repeat(2) { round ->
                val listening = CompletableFuture<Unit>()
                val received = CompletableFuture<ByteArray>()
                transport.listen(samePort, { received.complete(it) }, { if (it.startsWith("Nasłuch")) listening.complete(Unit) })
                listening.get(5, TimeUnit.SECONDS)
                val sent = CompletableFuture<Result<Unit>>()
                val report = "round-$round".toByteArray()
                transport.send("127.0.0.1:$samePort", report) { sent.complete(it) }
                assertTrue(sent.get(5, TimeUnit.SECONDS).isSuccess)
                assertArrayEquals(report, received.get(5, TimeUnit.SECONDS))
                transport.close()
            }
        } finally { transport.close() }
    }
}
