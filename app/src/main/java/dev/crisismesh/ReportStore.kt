// ReportStore.kt - local-only persistence. Files never leave app-private
// storage; there is no repository, sync or network call in this class.

package dev.crisismesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredReport(
    val id: String,
    val createdAtMillis: Long,
    val source: String, // "form" | "import"
    val report: Codec.Report,
    val note: String, // free text, stored locally, never packed into CM1
)

class ReportStore(private val context: Context) {
    private val file: File get() = File(context.filesDir, "reports.json")

    fun load(): List<StoredReport> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun count(key: String): Int? =
                if (o.isNull(key)) null else o.getInt(key)
            StoredReport(
                id = o.getString("id"),
                createdAtMillis = o.getLong("createdAtMillis"),
                source = o.getString("source"),
                report = Codec.validate(
                    count("people"), count("children"),
                    o.getString("hazard"), o.getString("need"),
                ),
                note = o.optString("note", ""),
            )
        }
    }

    fun add(report: Codec.Report, source: String, note: String): StoredReport {
        val item = StoredReport(
            id = System.currentTimeMillis().toString() + "-" + (0..999).random(),
            createdAtMillis = System.currentTimeMillis(),
            source = source,
            report = Codec.validate(report.people, report.children, report.hazard, report.need),
            note = note.take(2000),
        )
        val current = load()
        file.writeText(encode(current + item), Charsets.UTF_8)
        return item
    }

    fun delete(id: String) {
        file.writeText(encode(load().filterNot { it.id == id }), Charsets.UTF_8)
    }

    private fun encode(items: List<StoredReport>): String {
        val array = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("createdAtMillis", item.createdAtMillis)
            o.put("source", item.source)
            fun put(key: String, n: Int?) =
                if (n == null) o.put(key, JSONObject.NULL) else o.put(key, n)
            put("people", item.report.people)
            put("children", item.report.children)
            o.put("hazard", item.report.hazard)
            o.put("need", item.report.need)
            o.put("note", item.note)
            array.put(o)
        }
        return array.toString()
    }
}
