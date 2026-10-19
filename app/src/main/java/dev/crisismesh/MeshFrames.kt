package dev.crisismesh

/** Length-prefix framing for ordered BLE writes; wire layout matches TCP. */
object MeshFrames {
    const val MAX_PAYLOAD = 1024
    const val ATT_CHUNK = 20 // Fits the mandatory MTU 23, without negotiation.
    private const val HEADER = 4

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_PAYLOAD) { "Invalid payload length" }
        return java.nio.ByteBuffer.allocate(HEADER + payload.size)
            .putInt(payload.size).put(payload).array()
    }

    fun chunks(frame: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0) { "Chunk size must be positive" }
        require(frame.size >= HEADER) { "Truncated frame" }
        return (frame.indices step chunkSize).map { start ->
            frame.copyOfRange(start, minOf(start + chunkSize, frame.size))
        }
    }

    /** Fixed storage per connected peer. Invalid lengths reset state and fail the ATT write. */
    class Assembler {
        private val buffer = ByteArray(HEADER + MAX_PAYLOAD)
        private var used = 0
        private var end = HEADER

        fun feed(chunk: ByteArray): List<ByteArray> {
            require(chunk.size <= HEADER + MAX_PAYLOAD) { "Oversized fragment" }
            val done = ArrayList<ByteArray>()
            var offset = 0
            while (offset < chunk.size) {
                val count = minOf(end - used, chunk.size - offset)
                chunk.copyInto(buffer, used, offset, offset + count)
                used += count
                offset += count
                if (used == HEADER && end == HEADER) {
                    val length = java.nio.ByteBuffer.wrap(buffer, 0, HEADER).int
                    if (length !in 1..MAX_PAYLOAD) {
                        reset()
                        throw IllegalArgumentException("Invalid frame length")
                    }
                    end = HEADER + length
                }
                if (used == end) {
                    done.add(buffer.copyOfRange(HEADER, end))
                    reset()
                }
            }
            return done
        }

        fun reset() {
            used = 0
            end = HEADER
        }
    }
}
