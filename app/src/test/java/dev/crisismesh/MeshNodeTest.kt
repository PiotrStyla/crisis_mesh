package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MeshNodeTest {
    private val packet = "CM1|9|4|collapse|rescue".toByteArray(Charsets.US_ASCII)

    private class Node : AutoCloseable {
        val mesh = MeshNode()
        val packets = LinkedBlockingQueue<ByteArray>()
        val statuses = LinkedBlockingQueue<String>()
        val port: Int
        init {
            mesh.listen(0, { packets.add(it) }, { statuses.add(it) })
            val ready = statuses.poll(5, TimeUnit.SECONDS) ?: error("Listener not ready")
            check(ready.startsWith("Nasłuch")) { ready }
            port = ready.substringAfterLast(' ').toInt()
        }
        val address get() = "127.0.0.1:$port"
        fun peers(vararg nodes: Node) = mesh.configurePeers(nodes.joinToString(",") { it.address })
        fun receive(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: error("No report received")
        fun inject(bytes: ByteArray) {
            Socket("127.0.0.1", port).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
                check(socket.getInputStream().read() == 6) { "No frame receipt" }
            }
        }
        override fun close() = mesh.close()
    }

    @Test fun chainForwardsWithoutDirectOriginDestinationLink() {
        Node().use { a -> Node().use { b -> Node().use { c ->
            a.peers(b); b.peers(c)
            a.mesh.publish(packet, {})
            assertArrayEquals(packet, b.receive())
            assertArrayEquals(packet, c.receive())
            assertNull(a.packets.poll())
        } } }
    }

    @Test fun cycleDoesNotRedeliverToOriginOrAppendDuplicates() {
        Node().use { a -> Node().use { b -> Node().use { c ->
            a.peers(b, c); b.peers(a, c); c.peers(a, b)
            a.mesh.publish(packet, {})
            assertArrayEquals(packet, b.receive())
            assertArrayEquals(packet, c.receive())
            assertNull(a.packets.poll(500, TimeUnit.MILLISECONDS))
            assertNull(b.packets.poll(500, TimeUnit.MILLISECONDS))
            assertNull(c.packets.poll(500, TimeUnit.MILLISECONDS))
        } } }
    }

    @Test fun singleHopDeliversToNeighborButNotBeyond() {
        Node().use { a -> Node().use { b -> Node().use { c ->
            a.peers(b); b.peers(c)
            a.mesh.publish(packet, {}, maxHops = 1)
            assertArrayEquals(packet, b.receive())
            assertNull(c.packets.poll(500, TimeUnit.MILLISECONDS))
        } } }
    }

    @Test fun identityNotPayloadControlsDeduplication() {
        Node().use { receiver ->
            val first = MeshMessage(UUID.randomUUID(), 0, packet)
            receiver.inject(first.encode())
            assertArrayEquals(packet, receiver.receive())
            receiver.inject(first.encode())
            receiver.inject(first.copy(id = UUID.randomUUID()).encode())
            assertArrayEquals(packet, receiver.receive())
            assertNull(receiver.packets.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun invalidPayloadCannotPoisonIdOrReachNextHop() {
        Node().use { b -> Node().use { c ->
            b.peers(c)
            val good = MeshMessage(UUID.randomUUID(), 1, packet).encode()
            val bad = good.copyOf().apply { this[lastIndex] = '!'.code.toByte() }
            b.inject(bad)
            assertTrue(b.statuses.poll(5, TimeUnit.SECONDS).startsWith("Odrzucono"))
            assertNull(b.packets.poll())
            assertNull(c.packets.poll())
            b.inject(good)
            assertArrayEquals(packet, b.receive())
            assertArrayEquals(packet, c.receive())
        } }
    }

    @Test fun rejectsUnknownVersionAndInvalidHopBudget() {
        val bytes = MeshMessage(UUID.randomUUID(), 0, packet).encode()
        for (bad in listOf(bytes.copyOf().apply { this[3] = '2'.code.toByte() },
            bytes.copyOf().apply { this[20] = 8 }, bytes.copyOf(20))) {
            assertThrows(IllegalArgumentException::class.java) { MeshMessage.decode(bad) }
        }
    }
}
