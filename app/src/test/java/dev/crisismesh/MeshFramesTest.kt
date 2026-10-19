package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test

class MeshFramesTest {
    @Test fun fragmentedEnvelopePreservesIdentityAndReport() {
        val message = MeshMessage(java.util.UUID.randomUUID(), 3,
            "CM1|5|1|unknown|water".toByteArray(Charsets.US_ASCII))
        for (size in listOf(1, 3, 4, 7, 20)) {
            val assembler = MeshFrames.Assembler()
            val chunks = MeshFrames.chunks(MeshFrames.frame(message.encode()), size)
            chunks.dropLast(1).forEach { assertTrue(assembler.feed(it).isEmpty()) }
            val complete = assembler.feed(chunks.last()).single()
            val decoded = MeshMessage.decode(complete)
            assertEquals(message.id, decoded.id)
            assertEquals(message.hopsLeft, decoded.hopsLeft)
            assertArrayEquals(message.payload, decoded.payload)
        }
    }

    @Test fun consecutiveFramesDoNotLeakBytesBetweenMessages() {
        val first = "CM1|1|0|fire|rescue".toByteArray()
        val second = "CM1|2|1|flood|water".toByteArray()
        val stream = MeshFrames.frame(first) + MeshFrames.frame(second)
        val assembler = MeshFrames.Assembler()
        val out = MeshFrames.chunks(stream, 13).flatMap { assembler.feed(it) }
        assertEquals(2, out.size)
        assertArrayEquals(first, out[0])
        assertArrayEquals(second, out[1])
    }

    @Test fun payloadLengthBoundsIncludeOneAnd1024() {
        for (size in listOf(1, 1024)) {
            val input = ByteArray(size) { (it % 251).toByte() }
            val assembler = MeshFrames.Assembler()
            val out = MeshFrames.chunks(MeshFrames.frame(input), 20).flatMap { assembler.feed(it) }
            assertArrayEquals(input, out.single())
        }
        for (size in listOf(0, 1025)) {
            assertThrows(IllegalArgumentException::class.java) { MeshFrames.frame(ByteArray(size)) }
        }
    }

    @Test fun invalidLengthsFailAndResetThePeerStream() {
        for (bad in listOf(0, -1, 1025, Int.MAX_VALUE)) {
            val assembler = MeshFrames.Assembler()
            val bytes = java.nio.ByteBuffer.allocate(4).putInt(bad).array()
            assertThrows(IllegalArgumentException::class.java) { assembler.feed(bytes) }
            assertArrayEquals(byteArrayOf(42), assembler.feed(MeshFrames.frame(byteArrayOf(42))).single())
        }
    }

    @Test fun disconnectedPeerCannotCompleteAnOldPartialFrame() {
        val assembler = MeshFrames.Assembler()
        assembler.feed(byteArrayOf(0, 0, 0, 3, 1))
        assembler.reset()
        assertArrayEquals(byteArrayOf(9), assembler.feed(MeshFrames.frame(byteArrayOf(9))).single())
    }
}
