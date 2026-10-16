// AppCodecTest.kt - same invariants as test_codec.py: the Kotlin and Python
// codecs must reject the same inputs, or a packet produced by one side can be
// misread by the other.

package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test

class AppCodecTest {
    private fun report(
        people: Int? = null, children: Int? = null,
        hazard: String = "unknown", need: String = "unknown",
    ) = Codec.Report(people, children, hazard, need)

    private fun unpackBytes(raw: String) = Codec.unpack(raw.toByteArray(Charsets.UTF_8))

    @Test fun unknownAndZeroAreDistinct() {
        val unknown = report()
        val zero = report(0, 0)
        assertNotEquals(Codec.pack(unknown).toList(), Codec.pack(zero).toList())
        assertEquals(unknown, Codec.unpack(Codec.pack(unknown)))
        assertEquals(zero, Codec.unpack(Codec.pack(zero)))
        assertTrue(Codec.render(unknown).contains("nie podano"))
        assertTrue(Codec.render(zero).contains("Osoby łącznie: 0"))
    }

    @Test fun maximumAndAllCategoriesRoundtrip() {
        for (h in Codec.HAZARDS) for (n in Codec.NEEDS) {
            val r = report(254, 254, h, n)
            val packed = Codec.pack(r)
            assertTrue(packed.size <= Codec.MAX_BYTES)
            assertEquals(r, Codec.unpack(packed))
        }
    }

    @Test fun invalidCountsAreNotCoerced() {
        for ((field, bad) in listOf(
            "people" to -1, "people" to 255, "children" to -1, "children" to 255,
        )) {
            try {
                val (p, c) = if (field == "people") bad to null else null to bad
                Codec.validate(p, c, "unknown", "unknown")
                fail("expected rejection for $field=$bad")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
        // Booleans have no place in Kotlin typed data; equality with 1 rejected.
        assertNotEquals(report(1), report(1, 1))
    }

    @Test fun childrenAreSubset() {
        try {
            Codec.pack(report(1, 2))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) { /* expected */ }
        val r = report(null, 2)
        assertEquals(r, Codec.unpack(Codec.pack(r)))
    }

    @Test fun unknownFieldsAndCategoriesRejected() {
        for ((h, n) in listOf(
            "unknown" to "amputation", "safe" to "rescue", "fire" to "ok",
            "" to "rescue",
        )) {
            try {
                Codec.validate(null, null, h, n)
                fail("expected rejection for hazard=$h need=$n")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test fun noncanonicalOrCorruptPayloadRejected() {
        val payloads = listOf(
            "CM2|1|0|fire|rescue", "CM1|01|0|fire|rescue",
            "CM1|+1|0|fire|rescue", "CM1|1|0|fire|rescue\n",
            "CM1|1|0|fire|rescue|extra", "CM1|1|0|fire",
            "CM1|1|2|fire|rescue", "", "CM1|255|0|fire|rescue",
            "CM1|-1|0|fire|rescue", "CM1|٣|0|fire|rescue",
        )
        payloads.forEach { raw ->
            try {
                unpackBytes(raw)
                fail("expected rejection for '$raw'")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
        try {
            Codec.unpack(ByteArray(81) { 'a'.code.toByte() })
            fail("expected rejection for 81 bytes")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun roundtripMatchesPythonExample() {
        val r = report(3, 1, "unknown", "water")
        assertEquals("CM1|3|1|unknown|water", String(Codec.pack(r), Charsets.US_ASCII))
        assertEquals(r, Codec.unpackText("  CM1|3|1|unknown|water\n"))
        // Non-ASCII digits must never degrade to "?" (unknown) on import.
        try {
            Codec.unpackText("CM1|٣|0|fire|rescue")
            fail("expected rejection for foreign digit")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
