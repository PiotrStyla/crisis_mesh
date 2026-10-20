package dev.crisismesh

import android.content.Context
import android.content.Intent
import android.net.Uri

/** User confirms sending in the system SMS app. No SEND_SMS permission or silent forwarding. */
class ContactHandoff(private val context: Context) {
    private val prefs = context.getSharedPreferences("sos-contacts", Context.MODE_PRIVATE)
    fun contacts(): List<String> = parse(prefs.getString("numbers", "") ?: "")
    fun save(text: String): List<String> {
        val numbers = parse(text)
        check(prefs.edit().putString("numbers", numbers.joinToString(",")).commit()) { "Nie zapisano kontaktów" }
        return numbers
    }
    fun compose(number: String, item: StoredReport) {
        require(number in contacts()) { "Odbiorca nie jest zapisanym kontaktem" }
        val text = "Crisis Mesh — niezweryfikowane zgłoszenie użytkownika.\n${Codec.render(item.report)}\n" +
            "Pakiet: ${String(Codec.pack(item.report), Charsets.US_ASCII)}\nRaport: ${item.id}\n" +
            "To nie jest potwierdzenie wezwania ani przyjęcia pomocy przez służby."
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).apply {
            putExtra("sms_body", text)
        }
        // Let the system resolve the recipient app; catch missing handlers in the caller.
        context.startActivity(intent)
    }
    companion object {
        private val PHONE = Regex("\\+?[0-9]{5,15}")
        fun parse(text: String): List<String> {
            val numbers = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(numbers.size <= 5 && numbers.all { PHONE.matches(it) }) {
                "Podaj do 5 numerów, po przecinku; 5–15 cyfr, opcjonalnie + na początku."
            }
            return numbers
        }
    }
}
