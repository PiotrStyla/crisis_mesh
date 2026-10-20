package dev.crisismesh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class StoredReport(
    val id: String,
    val createdAtMillis: Long,
    val source: String,
    val report: Codec.Report,
    val note: String,
)

data class PendingDelivery(val messageId: String, val kind: String, val peer: String, val frame: ByteArray)

/** History, durable UUID tombstones and per-neighbor deliveries share one SQLite transaction.
 * Legacy JSON is imported exactly once, never rewritten or deleted. It remains a migration backup.
 */
class ReportStore(
    context: Context,
    databaseName: String = "crisis.db",
    legacyFile: File = File(context.filesDir, "reports.json"),
) : AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE reports (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, source TEXT NOT NULL, payload BLOB NOT NULL, note TEXT NOT NULL)")
            db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, digest BLOB NOT NULL, report_id TEXT REFERENCES reports(id) ON DELETE SET NULL)")
            db.execSQL("CREATE TABLE outbox (message_id TEXT NOT NULL REFERENCES messages(id), kind TEXT NOT NULL, peer TEXT NOT NULL, frame BLOB, state INTEGER NOT NULL DEFAULT 0 CHECK(state IN (0,1)), attempts INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', PRIMARY KEY(message_id,kind,peer))")
            db.execSQL("CREATE INDEX pending_deliveries ON outbox(kind,state,attempts)")
            db.execSQL("CREATE TABLE neighbors (kind TEXT NOT NULL, peer TEXT NOT NULL, PRIMARY KEY(kind,peer))")
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("ALTER TABLE messages ADD COLUMN forward_frame BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN forward_kind TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion == 1 && newVersion == 2) { "Unsupported database migration $oldVersion -> $newVersion" }
            db.execSQL("ALTER TABLE messages ADD COLUMN forward_frame BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN forward_kind TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER")
            // Only unfinished v1 envelopes are recoverable. Never republish completed history.
            db.execSQL("UPDATE messages SET forward_frame=(SELECT frame FROM outbox WHERE message_id=messages.id AND state=0 AND frame IS NOT NULL LIMIT 1), forward_kind=(SELECT kind FROM outbox WHERE message_id=messages.id AND state=0 AND frame IS NOT NULL LIMIT 1)")
            db.execSQL("UPDATE messages SET expires_at=? WHERE forward_frame IS NOT NULL",
                arrayOf(System.currentTimeMillis() + RETENTION_MILLIS))
        }
    }

    init {
        try {
            transaction { db ->
                if (setting(db, "legacy_imported") != "1") {
                    if (legacyFile.exists()) {
                        val rows = JSONArray(legacyFile.readText(Charsets.UTF_8))
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            fun count(key: String): Int? = if (row.isNull(key)) null else row.getInt(key)
                            insertReport(db, row.getString("id"), row.getLong("createdAtMillis"),
                                row.getString("source"), Codec.pack(Codec.validate(count("people"), count("children"),
                                    row.getString("hazard"), row.getString("need"))), row.optString("note", ""))
                        }
                    }
                    putSetting(db, "legacy_imported", "1")
                }
            }
        } catch (e: Exception) {
            helper.close()
            throw e // Do not discard a broken file, partially import it, or mark migration complete.
        }
    }

    @Synchronized private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun insertReport(db: SQLiteDatabase, id: String, created: Long, source: String, payload: ByteArray, note: String) {
        db.insertOrThrow("reports", null, ContentValues().apply {
            put("id", id); put("created_at", created); put("source", source); put("payload", payload); put("note", note)
        })
    }

    @Synchronized fun load(): List<StoredReport> = helper.readableDatabase.rawQuery(
        "SELECT id,created_at,source,payload,note FROM reports ORDER BY rowid", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(StoredReport(cursor.getString(0), cursor.getLong(1),
                cursor.getString(2), Codec.unpack(cursor.getBlob(3)), cursor.getString(4)))
        }
    }

    /** Only published/received reports; drafts and private notes never enter the board. */
    @Synchronized fun publicReports(): List<StoredReport> = helper.readableDatabase.rawQuery(
        "SELECT id,created_at,source,payload FROM reports WHERE EXISTS (SELECT 1 FROM messages WHERE report_id=reports.id) ORDER BY rowid DESC LIMIT 100", null,
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(StoredReport(rows.getString(0), rows.getLong(1),
                rows.getString(2), Codec.unpack(rows.getBlob(3)), ""))
        }
    }

    fun add(report: Codec.Report, source: String, note: String): StoredReport {
        val payload = Codec.pack(report)
        val item = StoredReport(UUID.randomUUID().toString(), System.currentTimeMillis(), source, report, note.take(2000))
        transaction { insertReport(it, item.id, item.createdAtMillis, item.source, payload, item.note) }
        return item
    }

    /** Deleting history also cancels related pending deliveries; UUID tombstones survive. */
    fun delete(id: String) = transaction { db ->
        db.execSQL("DELETE FROM outbox WHERE message_id IN (SELECT id FROM messages WHERE report_id=?)", arrayOf(id))
        db.execSQL("UPDATE messages SET forward_frame=NULL WHERE report_id=?", arrayOf(id))
        db.delete("reports", "id=?", arrayOf(id))
        Unit
    }

    private fun setting(db: SQLiteDatabase, key: String): String? = db.rawQuery(
        "SELECT value FROM settings WHERE key=?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putSetting(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", arrayOf(key, value))
    }
    @Synchronized fun selectedTransport(): String = setting(helper.readableDatabase, "transport") ?: "ble"
    fun selectTransport(kind: String) = transaction { putSetting(it, "transport", kind) }
    @Synchronized fun listenRequested(kind: String): Boolean = setting(helper.readableDatabase, "listen_$kind") == "1"
    fun requestListen(kind: String, enabled: Boolean) = transaction { putSetting(it, "listen_$kind", if (enabled) "1" else "0") }

    fun nodeId(): String = transaction { db ->
        setting(db, "node_id") ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            putSetting(db, "node_id", it)
        }
    }
    @Synchronized fun autoMeshEnabled(): Boolean = setting(helper.readableDatabase, "auto_ble") == "1"
    fun setAutoMesh(enabled: Boolean) = transaction { db ->
        putSetting(db, "auto_ble", if (enabled) "1" else "0")
        putSetting(db, "listen_ble", if (enabled) "1" else "0")
        if (enabled) {
            db.delete("neighbors", "kind='ble'", null)
            // MAC-addressed v1 work is replaced by stable-identity destinations on discovery.
            db.delete("outbox", "kind='ble' AND state=0", null)
            db.rawQuery("SELECT id,forward_frame FROM messages WHERE forward_kind='ble' AND forward_frame IS NOT NULL AND expires_at>?",
                arrayOf(System.currentTimeMillis().toString())).use { rows ->
                while (rows.moveToNext()) queue(db, rows.getString(0), "ble", "", rows.getBlob(1))
            }
        }
    }

    /** A new endpoint for the same stable peer never creates another delivery. */
    fun discoverPeer(peer: String, now: Long = System.currentTimeMillis()) = transaction { db ->
        require(NODE_PEER.matches(peer)) { "Invalid node identity" }
        db.execSQL("INSERT OR IGNORE INTO neighbors(kind,peer) VALUES('ble',?)", arrayOf(peer))
        db.execSQL("DELETE FROM neighbors WHERE kind='ble' AND rowid NOT IN (SELECT rowid FROM neighbors WHERE kind='ble' ORDER BY rowid DESC LIMIT 64)")
        db.rawQuery("SELECT id,forward_frame FROM messages WHERE forward_kind='ble' AND forward_frame IS NOT NULL AND expires_at>?",
            arrayOf(now.toString())).use { rows ->
            while (rows.moveToNext()) {
                queue(db, rows.getString(0), "ble", peer, rows.getBlob(1))
                db.delete("outbox", "message_id=? AND kind='ble' AND peer=''", arrayOf(rows.getString(0)))
            }
        }
    }

    fun expireForwarding(now: Long = System.currentTimeMillis()) = transaction { db ->
        val removed = db.compileStatement("DELETE FROM outbox WHERE state=0 AND message_id IN (SELECT id FROM messages WHERE expires_at<=?)").use {
            it.bindLong(1, now)
            it.executeUpdateDelete()
        }
        db.execSQL("UPDATE messages SET forward_frame=NULL WHERE forward_frame IS NOT NULL AND expires_at<=?", arrayOf(now))
        removed
    }

    private fun peers(db: SQLiteDatabase, kind: String): List<String> = db.rawQuery(
        "SELECT peer FROM neighbors WHERE kind=? ORDER BY rowid", arrayOf(kind),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    @Synchronized fun peers(kind: String): List<String> = peers(helper.readableDatabase, kind)

    fun configurePeers(kind: String, next: List<String>) = transaction { db ->
        db.delete("neighbors", "kind=?", arrayOf(kind))
        next.forEach { peer -> db.insertOrThrow("neighbors", null, ContentValues().apply { put("kind", kind); put("peer", peer) }) }
        if (next.isNotEmpty()) {
            val waiting = deliveries(db, kind)
            waiting.forEach { item ->
                next.forEach { queue(db, item.messageId, kind, it, item.frame) }
                db.delete("outbox", "message_id=? AND kind=? AND peer=''", arrayOf(item.messageId, kind))
            }
        }
        Unit
    }

    private fun digest(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)
    private fun remember(db: SQLiteDatabase, message: MeshMessage, reportId: String?, kind: String, forward: MeshMessage?) {
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", message.id.toString()); put("digest", digest(message.payload)); put("report_id", reportId)
            put("forward_kind", kind); put("forward_frame", forward?.encode())
            put("expires_at", System.currentTimeMillis() + RETENTION_MILLIS)
        })
    }
    private fun queue(db: SQLiteDatabase, id: String, kind: String, peer: String, frame: ByteArray) {
        db.execSQL("INSERT OR IGNORE INTO outbox(message_id,kind,peer,frame) VALUES(?,?,?,?)", arrayOf(id, kind, peer, frame))
    }
    private fun fanout(db: SQLiteDatabase, message: MeshMessage, kind: String) {
        val frame = message.encode()
        val targets = peers(db, kind).ifEmpty { listOf("") } // Persist even before a neighbor is configured.
        targets.forEach { queue(db, message.id.toString(), kind, it, frame) }
    }

    fun enqueue(payload: ByteArray, kind: String, maxHops: Int, reportId: String?): UUID {
        require(maxHops in 1..8) { "Hop limit must be 1..8" }
        Codec.unpack(payload)
        val message = MeshMessage(UUID.randomUUID(), maxHops - 1, payload)
        transaction { db ->
            if (reportId != null) {
                db.rawQuery("SELECT payload FROM reports WHERE id=?", arrayOf(reportId)).use {
                    require(it.moveToFirst() && it.getBlob(0).contentEquals(payload)) { "Raport został usunięty lub zmieniony" }
                }
            }
            remember(db, message, reportId, kind, message)
            fanout(db, message, kind)
        }
        return message.id
    }

    /** Report and outgoing UUID commit together; no model or permission gate is involved. */
    fun addAndEnqueue(report: Codec.Report, note: String, kind: String, maxHops: Int = 4): StoredReport {
        require(maxHops in 1..8)
        val payload = Codec.pack(report)
        val item = StoredReport(UUID.randomUUID().toString(), System.currentTimeMillis(), "form", report, note.take(2000))
        val message = MeshMessage(UUID.randomUUID(), maxHops - 1, payload)
        transaction { db ->
            insertReport(db, item.id, item.createdAtMillis, item.source, payload, item.note)
            remember(db, message, item.id, kind, message)
            fanout(db, message, kind)
        }
        return item
    }

    /** Returns only after COMMIT. False is a previously committed duplicate, not a rejection. */
    fun accept(message: MeshMessage, kind: String): Boolean {
        Codec.unpack(message.payload)
        return transaction { db ->
            val previous = db.rawQuery("SELECT digest FROM messages WHERE id=?", arrayOf(message.id.toString())).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }
            if (previous != null) {
                require(previous.contentEquals(digest(message.payload))) { "UUID ma inną treść" }
                false
            } else {
                val id = message.id.toString()
                insertReport(db, id, System.currentTimeMillis(), "mesh", message.payload, "")
                remember(db, message, id, kind, if (message.hopsLeft > 0) message.copy(hopsLeft = message.hopsLeft - 1) else null)
                if (message.hopsLeft > 0) fanout(db, message.copy(hopsLeft = message.hopsLeft - 1), kind)
                true
            }
        }
    }

    private fun deliveries(db: SQLiteDatabase, kind: String): List<PendingDelivery> = db.rawQuery(
        "SELECT message_id,peer,frame FROM outbox WHERE kind=? AND state=0 AND peer='' ORDER BY attempts,rowid", arrayOf(kind),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(PendingDelivery(cursor.getString(0), kind, cursor.getString(1), cursor.getBlob(2)))
    } }
    @Synchronized fun nextPending(kind: String, reachable: Set<String>? = null): PendingDelivery? {
        if (reachable != null && reachable.isEmpty()) return null
        val filter = if (reachable == null) "" else " AND peer IN (${reachable.joinToString(",") { "?" }})"
        val args = arrayOf(kind, System.currentTimeMillis().toString()) + (reachable?.toTypedArray() ?: emptyArray())
        return helper.readableDatabase.rawQuery(
            "SELECT message_id,peer,frame FROM outbox WHERE kind=? AND state=0 AND peer<>'' " +
                "AND message_id IN (SELECT id FROM messages WHERE expires_at>?)$filter ORDER BY attempts,rowid LIMIT 1", args,
        ).use { if (it.moveToFirst()) PendingDelivery(it.getString(0), kind, it.getString(1), it.getBlob(2)) else null }
    }

    fun recordAttempt(item: PendingDelivery, result: Result<Unit>) = transaction { db ->
        if (result.isSuccess) {
            db.execSQL("UPDATE outbox SET state=1,frame=NULL,attempts=attempts+1,error='' WHERE message_id=? AND kind=? AND peer=? AND state=0",
                arrayOf(item.messageId, item.kind, item.peer))
        } else {
            db.execSQL("UPDATE outbox SET attempts=attempts+1,error=? WHERE message_id=? AND kind=? AND peer=? AND state=0",
                arrayOf((result.exceptionOrNull()?.message ?: "Błąd dostarczenia").take(300), item.messageId, item.kind, item.peer))
        }
    }


    @Synchronized fun queueSummary(kind: String): String = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*),COALESCE(SUM(CASE WHEN peer='' THEN 1 ELSE 0 END),0) FROM outbox WHERE kind=? AND state=0", arrayOf(kind),
    ).use { cursor ->
        cursor.moveToFirst()
        val pending = cursor.getInt(0)
        val waiting = cursor.getInt(1)
        val confirmed = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE kind=? AND state=1", arrayOf(kind)).use {
            it.moveToFirst(); it.getInt(0)
        }
        "Oczekujące dostarczenia: $pending · bez sąsiadów: $waiting · zapis u sąsiada potwierdzony: $confirmed"
    }
    @Synchronized override fun close() = helper.close()

    companion object {
        private val NODE_PEER = Regex("node:[0-9a-f]{16}")
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    }
}
