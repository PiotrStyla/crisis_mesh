package dev.crisismesh

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutoMeshStoreTest {
    private val payload = "CM1|5|1|unknown|water".toByteArray()
    private val first = "node:0123456789abcdef"
    private val second = "node:fedcba9876543210"
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "auto-${UUID.randomUUID()}.db"
        var store: ReportStore? = null
        fun open(): ReportStore {
            store?.close()
            return ReportStore(context, name, File(context.cacheDir, "$name-absent.json")).also { store = it }
        }
        override fun close() { store?.close(); context.deleteDatabase(name) }
    }

    @Test fun laterNeighborGetsOriginalFrameAfterReceiptAndRestartWithoutRediscoveryDuplicates() {
        Fixture().use { f ->
            var store = f.open()
            store.setAutoMesh(true)
            val report = store.addAndEnqueue(Codec.unpack(payload), "private", "ble")
            store.discoverPeer(first)
            val original = store.nextPending("ble")!!
            store.recordAttempt(original, Result.success(Unit))
            store = f.open()
            store.discoverPeer(first)
            assertNull(store.nextPending("ble"))
            store.discoverPeer(second)
            val later = store.nextPending("ble")!!
            assertEquals(second, later.peer)
            assertArrayEquals(original.frame, later.frame)
            store.discoverPeer(second)
            store.recordAttempt(later, Result.success(Unit))
            assertNull(store.nextPending("ble"))
            assertEquals(report.id, store.load().single().id)
            assertEquals("private", store.load().single().note)
        }
    }

    @Test fun missingNeighborDoesNotBlockReachableNeighborAndDeletionCancelsFutureFanout() {
        Fixture().use { f ->
            val store = f.open()
            store.setAutoMesh(true)
            store.discoverPeer(first)
            store.discoverPeer(second)
            val report = store.addAndEnqueue(Codec.unpack(payload), "", "ble")
            val pending = store.nextPending("ble", setOf(second))!!
            assertEquals(second, pending.peer)
            val id = MeshMessage.decode(pending.frame).id
            store.delete(report.id)
            store.discoverPeer("node:1111111111111111")
            assertNull(store.nextPending("ble"))
            assertFalse(store.accept(MeshMessage(id, 3, payload), "ble"))
            assertTrue(store.load().isEmpty())
        }
    }

    @Test fun hopBoundaryAndDuplicateDoNotReplenishForwardingBudget() {
        Fixture().use { f ->
            val store = f.open()
            store.setAutoMesh(true)
            val stopped = MeshMessage(UUID.randomUUID(), 0, payload)
            assertTrue(store.accept(stopped, "ble"))
            store.discoverPeer(first)
            assertNull(store.nextPending("ble"))
            val incoming = MeshMessage(UUID.randomUUID(), 1, payload)
            assertTrue(store.accept(incoming, "ble"))
            val forward = store.nextPending("ble")!!
            assertEquals(0, MeshMessage.decode(forward.frame).hopsLeft)
            store.recordAttempt(forward, Result.success(Unit))
            assertFalse(store.accept(incoming.copy(hopsLeft = 7), "ble"))
            store.discoverPeer(second)
            val later = MeshMessage.decode(store.nextPending("ble")!!.frame)
            assertEquals(incoming.id, later.id)
            assertEquals(0, later.hopsLeft)
            assertEquals(2, store.load().size)
        }
    }

    @Test fun retentionExpiresDeliveriesButPreservesHistoryAndTombstone() {
        Fixture().use { f ->
            val store = f.open()
            store.setAutoMesh(true)
            val report = store.addAndEnqueue(Codec.unpack(payload), "kept", "ble")
            store.discoverPeer(first)
            val id = MeshMessage.decode(store.nextPending("ble")!!.frame).id
            val future = System.currentTimeMillis() + ReportStore.RETENTION_MILLIS + 1000
            assertEquals(1, store.expireForwarding(future))
            assertEquals(0, store.expireForwarding(future))
            store.discoverPeer(second, future)
            assertNull(store.nextPending("ble"))
            assertEquals(report.id, store.load().single().id)
            assertFalse(store.accept(MeshMessage(id, 3, payload), "ble"))
        }
    }

    @Test fun reportAndQueueRollbackTogetherWhenOutboxWriteFails() {
        Fixture().use { f ->
            val store = f.open()
            SQLiteDatabase.openDatabase(f.context.getDatabasePath(f.name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TRIGGER fail_sos BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'full'); END")
            }
            assertThrows(Exception::class.java) { store.addAndEnqueue(Codec.unpack(payload), "private", "ble") }
            assertTrue(store.load().isEmpty())
            store.discoverPeer(first)
            assertNull(store.nextPending("ble"))
        }
    }

    @Test fun versionOneMigrationKeepsPendingUuidAndHistoryButDoesNotRepublishCompletedMessages() {
        Fixture().use { f ->
            val waiting = MeshMessage(UUID.randomUUID(), 2, payload)
            val finished = UUID.randomUUID()
            val path = f.context.getDatabasePath(f.name)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE reports(id TEXT PRIMARY KEY,created_at INTEGER NOT NULL,source TEXT NOT NULL,payload BLOB NOT NULL,note TEXT NOT NULL)")
                db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,digest BLOB NOT NULL,report_id TEXT REFERENCES reports(id) ON DELETE SET NULL)")
                db.execSQL("CREATE TABLE outbox(message_id TEXT NOT NULL REFERENCES messages(id),kind TEXT NOT NULL,peer TEXT NOT NULL,frame BLOB,state INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',PRIMARY KEY(message_id,kind,peer))")
                db.execSQL("CREATE TABLE neighbors(kind TEXT NOT NULL,peer TEXT NOT NULL,PRIMARY KEY(kind,peer))")
                db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                db.execSQL("INSERT INTO reports VALUES('original',1234,'form',?,'keep me')", arrayOf(payload))
                val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                db.execSQL("INSERT INTO messages VALUES(?,?,'original')", arrayOf(waiting.id.toString(), digest))
                db.execSQL("INSERT INTO messages VALUES(?,?,NULL)", arrayOf(finished.toString(), digest))
                db.execSQL("INSERT INTO outbox(message_id,kind,peer,frame,state) VALUES(?,'ble','AA:BB:CC:DD:EE:FF',?,0)", arrayOf(waiting.id.toString(), waiting.encode()))
                db.execSQL("INSERT INTO outbox(message_id,kind,peer,frame,state) VALUES(?,'ble','AA:BB:CC:DD:EE:FF',NULL,1)", arrayOf(finished.toString()))
                db.version = 1
            }
            val store = f.open()
            val identity = store.nodeId()
            store.setAutoMesh(true)
            store.discoverPeer(first)
            val pending = store.nextPending("ble")!!
            assertEquals(waiting.id, MeshMessage.decode(pending.frame).id)
            assertArrayEquals(waiting.encode(), pending.frame)
            store.recordAttempt(pending, Result.success(Unit))
            assertNull(store.nextPending("ble"))
            assertEquals("keep me", store.load().single().note)
            assertEquals(1234L, store.load().single().createdAtMillis)
            assertEquals(identity, f.open().nodeId())
        }
    }
    @Test fun publicBoardExcludesDraftsAndPrivateNotes() {
        Fixture().use { f ->
            val store = f.open()
            store.add(Codec.unpack(payload), "form", "private draft")
            val published = store.addAndEnqueue(Codec.unpack(payload), "secret note", "ble")
            val received = MeshMessage(UUID.randomUUID(), 0, payload)
            store.accept(received, "ble")
            val visible = store.publicReports()
            assertEquals(setOf(published.id, received.id.toString()), visible.map { it.id }.toSet())
            assertTrue(visible.all { it.note.isEmpty() })
            assertEquals(3, store.load().size)
            assertEquals("secret note", store.load().first { it.id == published.id }.note)
        }
    }
}
