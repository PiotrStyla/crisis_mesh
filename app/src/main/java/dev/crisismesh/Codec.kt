// Codec.kt - Kotlin mirror of codec.py. One schema, one wire format: any
// divergence between the two implementations would corrupt packets silently,
// so validate/pack/unpack/render are kept behaviorally identical.
//
//   CM1|people|children|hazard|need      ASCII, no trailing newline, <= 80 B

package dev.crisismesh

object Codec {
    const val MAX_BYTES = 80
    const val VERSION = "CM1"

    val HAZARDS = listOf("unknown", "fire", "flood", "collapse")
    val NEEDS = listOf("unknown", "rescue", "medical", "water")

    private val HAZARD_PL = mapOf(
        "unknown" to "nie ustalono", "fire" to "pożar",
        "flood" to "powódź", "collapse" to "zawalenie",
    )
    private val NEED_PL = mapOf(
        "unknown" to "nie ustalono", "rescue" to "ratunek",
        "medical" to "pomoc medyczna", "water" to "woda",
    )

    data class Report(
        val people: Int?,
        val children: Int?,
        val hazard: String,
        val need: String,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "people" to people, "children" to children,
            "hazard" to hazard, "need" to need,
        )
    }

    fun validate(people: Int?, children: Int?, hazard: String, need: String): Report {
        for ((field, n) in listOf("people" to people, "children" to children)) {
            if (n != null && n !in 0..254)
                throw IllegalArgumentException("$field: expected null or integer 0..254")
        }
        if (hazard !in HAZARDS) throw IllegalArgumentException("Unknown hazard code")
        if (need !in NEEDS) throw IllegalArgumentException("Unknown need code")
        if (people != null && children != null && children > people)
            throw IllegalArgumentException("Children are a subset of total people")
        return Report(people, children, hazard, need)
    }

    private fun count(n: Int?): String = if (n == null) "?" else n.toString()

    fun pack(r: Report): ByteArray {
        val checked = validate(r.people, r.children, r.hazard, r.need)
        val ascii = "$VERSION|${count(checked.people)}|${count(checked.children)}|" +
            "${checked.hazard}|${checked.need}"
        val bytes = ascii.toByteArray(Charsets.US_ASCII)
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("Packet exceeds 80 bytes")
        return bytes
    }

    fun unpack(data: ByteArray): Report {
        if (data.size > MAX_BYTES)
            throw IllegalArgumentException("Expected bytes, at most 80")
        // Match Python bytes.decode("ascii"): any byte >127 is malformed input,
        // never coerced (US_ASCII String would silently turn '٣' into '?').
        if (data.any { it.toInt() and 0xFF > 127 })
            throw IllegalArgumentException("Malformed packet")
        val text = String(data, Charsets.US_ASCII)
        val parts = text.split("|")
        if (parts.size != 5) throw IllegalArgumentException("Malformed packet")
        val (version, people, children, hazard, need) = parts
        if (version != VERSION) throw IllegalArgumentException("Unsupported packet version")
        fun parse(s: String): Int? = when {
            s == "?" -> null
            s.isEmpty() || !s.all { it in '0'..'9' } ->
                throw IllegalArgumentException("Invalid count")
            else -> s.toIntOrNull() ?: throw IllegalArgumentException("Invalid count")
        }
        val report = try {
            validate(parse(people), parse(children), hazard, need)
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("Malformed") == true) throw e
            throw e
        }
        // Canonical form only: "01", "+1" or category case tricks are rejected
        // by re-encoding and comparing byte-for-byte.
        if (!pack(report).contentEquals(data))
            throw IllegalArgumentException("Noncanonical packet")
        return report
    }

    /** Import helper: callers trim surrounding carrier whitespace, codec stays strict. */
    fun unpackText(text: String): Report {
        val trimmed = text.trim()
        // Check chars before ASCII encoding, which would otherwise map any
        // non-ASCII character to '?' (i.e. "unknown") and hide malformed input.
        if (trimmed.any { it.code > 127 })
            throw IllegalArgumentException("Malformed packet")
        return unpack(trimmed.toByteArray(Charsets.US_ASCII))
    }

    fun render(r: Report): String {
        val c = { n: Int? -> if (n == null) "nie podano" else n.toString() }
        return "Osoby łącznie: ${c(r.people)}; w tym dzieci: ${c(r.children)}. " +
            "Zgłoszone zagrożenie: ${HAZARD_PL[r.hazard]}. " +
            "Zgłoszona potrzeba: ${NEED_PL[r.need]}. " +
            "Skrót czterech pól, nie pełna treść zgłoszenia."
    }
}
