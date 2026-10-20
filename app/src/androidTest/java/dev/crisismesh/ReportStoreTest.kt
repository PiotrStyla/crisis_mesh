package dev.crisismesh

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReportStoreTest {
    private val payload = "CM1|5|1|unknown|water".toByteArray()
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal-${UUID.randomUUID()}.db"
        val legacy = File(context.cacheDir, "legacy-${UUID.randomUUID()}.json")
        var current: ReportStore? = null
        fun open(): ReportStore {
            current?.close()
            current = null
            return ReportStore(context, name, legacy).also { current = it }
        }
        fun sql(statement: String) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL(statement)
            }
        }
        override fun close() {
            current?.close()
            context.deleteDatabase(name)
            legacy.delete()
        }
    }

    @Test fun migrationPreservesFieldsNotesAndIdsExactlyOnce() {
        Fixture().use { f ->
            val original = """[{"id":"legacy-id","createdAtMillis":1234,"source":"form","people":null,"children":0,"hazard":"unknown","need":"water","note":"Zażółć gęślą jaźń"}]"""
            f.legacy.writeText(original)
            var store = f.open()
            val migrated = store.load().single()
            assertEquals("legacy-id", migrated.id)
            assertEquals(1234L, migrated.createdAtMillis)
            assertNull(migrated.report.people)
            assertEquals(0, migrated.report.children)
            assertEquals("Zażółć gęślą jaźń", migrated.note)
            val added = store.add(Codec.unpack(payload), "import", "new")
            store = f.open()
            assertEquals(listOf("legacy-id", added.id), store.load().map { it.id })
            assertEquals(original, f.legacy.readText())
        }
    }

    @Test fun invalidLegacyRowRollsBackAllRowsAndDoesNotMarkImportComplete() {
        Fixture().use { f ->
            val good = """{"id":"first","createdAtMillis":1,"source":"form","people":1,"children":0,"hazard":"unknown","need":"water"}"""
            val bad = "[$good,{\"id\":\"broken\"}]"
            f.legacy.writeText(bad)
            assertThrows(Exception::class.java) { f.open() }
            assertEquals(bad, f.legacy.readText())
            f.legacy.writeText("[$good]")
            assertEquals("first", f.open().load().single().id) // Would fail on duplicate ID if the first row had leaked.
        }
    }

    @Test fun receiverRestartDeletionAndConflictingUuidDoNotResurrectReports() {
        Fixture().use { f ->
            val message = MeshMessage(UUID.randomUUID(), 0, payload)
            var store = f.open()
            assertTrue(store.accept(message, "tcp"))
            store = f.open()
            assertFalse(store.accept(message, "tcp"))
            assertEquals(message.id.toString(), store.load().single().id)
            store.delete(message.id.toString())
            store = f.open()
            assertFalse(store.accept(message, "tcp"))
            assertTrue(store.load().isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                store.accept(message.copy(payload = "CM1|9|1|fire|rescue".toByteArray()), "tcp")
            }
            assertTrue(store.load().isEmpty())
        }
    }

    @Test fun queueSurvivesRestartAndFailureWithSameUuidUntilCommitReceipt() {
        Fixture().use { f ->
            var store = f.open()
            val report = store.add(Codec.unpack(payload), "form", "local only")
            val id = store.enqueue(payload, "tcp", 4, report.id)
            assertNull(store.nextPending("tcp")) // Durable placeholder, no configured destination yet.
            store = f.open()
            store.configurePeers("tcp", listOf("127.0.0.1:8765"))
            val attempt = store.nextPending("tcp")!!
            assertEquals(id, MeshMessage.decode(attempt.frame).id)
            assertArrayEquals(payload, MeshMessage.decode(attempt.frame).payload)
            store.recordAttempt(attempt, Result.failure(java.io.IOException("lost connection")))
            store = f.open()
            val retry = store.nextPending("tcp")!!
            assertEquals(attempt.messageId, retry.messageId)
            assertArrayEquals(attempt.frame, retry.frame)
            store.recordAttempt(retry, Result.success(Unit))
            store = f.open()
            assertNull(store.nextPending("tcp"))
            assertEquals("local only", store.load().single().note)
        }
    }

    @Test fun incomingReportUuidAndForwardingQueueCommitOrRollbackTogether() {
        Fixture().use { f ->
            var store = f.open()
            store.configurePeers("tcp", listOf("127.0.0.1:8765"))
            val message = MeshMessage(UUID.randomUUID(), 2, payload)
            f.sql("CREATE TRIGGER fail_queue BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END")
            assertThrows(Exception::class.java) { store.accept(message, "tcp") }
            assertTrue(store.load().isEmpty())
            assertNull(store.nextPending("tcp"))
            f.sql("DROP TRIGGER fail_queue")
            assertTrue(store.accept(message, "tcp")) // UUID was rolled back too, not poisoned.
            store = f.open()
            val forward = MeshMessage.decode(store.nextPending("tcp")!!.frame)
            assertEquals(message.id, forward.id)
            assertEquals(1, forward.hopsLeft)
            assertFalse(store.accept(message, "tcp"))
            assertEquals(message.id.toString(), store.load().single().id)
        }
    }

}
