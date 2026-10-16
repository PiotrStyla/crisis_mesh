package dev.crisismesh

import java.nio.ByteBuffer
import java.util.UUID

/** CM1 stays unchanged; CMF1 is a versioned forwarding envelope, not authentication. */
data class MeshMessage(val id: UUID, val hopsLeft: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        require(hopsLeft in 0..7) { "Invalid hop budget" }
        Codec.unpack(payload)
        return ByteBuffer.allocate(21 + payload.size)
            .putInt(MAGIC).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .put(hopsLeft.toByte()).put(payload).array()
    }

    companion object {
        private const val MAGIC = 0x434d4631 // CMF1
        fun decode(bytes: ByteArray): MeshMessage {
            require(bytes.size in 22..101) { "Invalid envelope length" }
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.int == MAGIC) { "Unsupported mesh version" }
            val id = UUID(buffer.long, buffer.long)
            val hops = buffer.get().toInt() and 255
            require(hops in 0..7) { "Invalid hop budget" }
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            Codec.unpack(payload) // Reject before remembering or forwarding the ID.
            return MeshMessage(id, hops, payload)
        }
    }
}

/** Best-effort flooding over explicitly configured peers; no discovery or offline outbox. */
class MeshNode(private val transport: MeshTransport = TcpMeshTransport()) {
    @Volatile private var peers: List<String> = emptyList()
    private val seen = LinkedHashSet<UUID>()

    fun configurePeers(text: String) {
        val next = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(next.size <= 8) { "Maksymalnie 8 sąsiadów" }
        require(next.all { address ->
            val cut = address.lastIndexOf(':')
            cut > 0 && address.substring(0, cut).none { it.isWhitespace() } &&
                address.substring(cut + 1).toIntOrNull() in 1..65535
        }) { "Podaj sąsiadów jako host:port, oddzielonych przecinkiem" }
        peers = next
    }

    fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit) {
        transport.listen(port, receive@ { bytes ->
            val message = try {
                MeshMessage.decode(bytes)
            } catch (e: IllegalArgumentException) {
                onStatus("Odrzucono ramkę mesh: ${e.message}")
                return@receive
            }
            if (remember(message.id)) {
                onPacket(message.payload)
                if (message.hopsLeft > 0) {
                    forward(message.copy(hopsLeft = message.hopsLeft - 1), onStatus)
                }
            }
        }, onStatus)
    }

    /** A deliberate new send creates a new ID, even for identical report fields. */
    fun publish(payload: ByteArray, onStatus: (String) -> Unit, maxHops: Int = 4): UUID {
        require(maxHops in 1..8) { "Hop limit must be 1..8" }
        require(peers.isNotEmpty()) { "Najpierw zastosuj listę sąsiadów" }
        Codec.unpack(payload)
        val message = MeshMessage(UUID.randomUUID(), maxHops - 1, payload)
        remember(message.id) // A cycle must never deliver the origin's own report again.
        forward(message, onStatus)
        return message.id
    }

    private fun forward(message: MeshMessage, onStatus: (String) -> Unit) {
        val bytes = message.encode()
        peers.forEach { transport.send(it, bytes, onStatus) }
    }

    @Synchronized private fun remember(id: UUID): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > 4096) {
            val oldest = seen.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    fun close() = transport.close()
}
