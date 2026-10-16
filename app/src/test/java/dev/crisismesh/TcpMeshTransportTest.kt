// TcpMeshTransportTest.kt - behavioral roundtrip on JVM loopback: exact bytes
// out, exact bytes in, error paths, and restart after close.

package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TcpMeshTransportTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun roundtripDeliversExactBytes() {
        val transport = TcpMeshTransport()
        val port = freePort()
        val received = AtomicReference<ByteArray>()
        val gotPacket = CountDownLatch(1)
        val listening = CountDownLatch(1)
        val statusLog = AtomicReference("")
        transport.listen(port, { bytes -> received.set(bytes); gotPacket.countDown() },
            { s -> statusLog.set(s); if (s.contains("Nasłuch")) listening.countDown() })
        assertTrue("listener never started", listening.await(5, TimeUnit.SECONDS))

        val payload = "CM1|5|2|flood|rescue".toByteArray(Charsets.US_ASCII)
        val sent = CountDownLatch(1)
        val sendLog = AtomicReference("")
        transport.send("127.0.0.1:$port", payload, { s -> sendLog.set(s); sent.countDown() })
        assertTrue("send never completed", sent.await(5, TimeUnit.SECONDS))
        assertTrue("packet never arrived", gotPacket.await(5, TimeUnit.SECONDS))
        assertArrayEquals(payload, received.get())
        transport.close()
    }

    @Test fun badAddressReportsWithoutCrash() {
        val transport = TcpMeshTransport()
        val done = CountDownLatch(1)
        val log = AtomicReference("")
        transport.send("not-an-address", ByteArray(4)) { s -> log.set(s); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("got: ${log.get()}", log.get().contains("host:port"))
        transport.close()
    }

    @Test fun unreachablePeerReportsError() {
        val transport = TcpMeshTransport()
        val port = freePort() // nothing listens here
        val done = CountDownLatch(1)
        val log = AtomicReference("")
        transport.send("127.0.0.1:$port", ByteArray(4)) { s -> log.set(s); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("got: ${log.get()}", log.get().startsWith("Błąd"))
        transport.close()
    }

    @Test fun listenerRestartsAfterClose() {
        val transport = TcpMeshTransport()
        val port = freePort()
        repeat(2) { round ->
            val got = CountDownLatch(1)
            val received = AtomicReference<ByteArray>()
            val listening = CountDownLatch(1)
            transport.listen(port, { bytes -> received.set(bytes); got.countDown() },
                { s -> if (s.contains("Nasłuch")) listening.countDown() })
            assertTrue("listen round $round", listening.await(5, TimeUnit.SECONDS))
            val sent = CountDownLatch(1)
            transport.send("127.0.0.1:$port", "round-$round".toByteArray()) { sent.countDown() }
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            assertTrue("packet round $round", got.await(5, TimeUnit.SECONDS))
            assertEquals("round-$round", String(received.get()))
            transport.close()
        }
    }
}
