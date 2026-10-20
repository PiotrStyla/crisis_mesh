package dev.crisismesh

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** CMF2 requires a committed receive transaction before a successful transport receipt. */
data class MeshMessage(val id: UUID, val hopsLeft: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        require(hopsLeft in 0..7) { "Invalid hop budget" }
        Codec.unpack(payload)
        return ByteBuffer.allocate(21 + payload.size)
            .putInt(MAGIC).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .put(hopsLeft.toByte()).put(payload).array()
    }
    companion object {
        private const val MAGIC = 0x434d4632 // CMF2: no compatibility with frame-only receipts.
        fun decode(bytes: ByteArray): MeshMessage {
            require(bytes.size in 22..101) { "Invalid envelope length" }
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.int == MAGIC) { "Unsupported mesh version" }
            val id = UUID(buffer.long, buffer.long)
            val hops = buffer.get().toInt() and 255
            require(hops in 0..7) { "Invalid hop budget" }
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            Codec.unpack(payload)
            return MeshMessage(id, hops, payload)
        }
    }
}

/** Durable per-neighbor queue. Polling belongs to this live app instance, not a background service. */
class MeshNode(
    private val store: ReportStore,
    private val transport: MeshTransport,
    val kind: String,
    private val canSend: () -> Boolean = { true },
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mesh-outbox").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var changed: () -> Unit = {}
    @Volatile private var status: (String) -> Unit = {}
    @Volatile private var received: (ByteArray) -> Unit = {}

    fun start(onChanged: () -> Unit, onStatus: (String) -> Unit) {
        changed = onChanged
        status = onStatus
        if (kind == "ble" && store.autoMeshEnabled()) {
            if (canSend()) joinNetwork() else status("Dołączenie oczekuje na uprawnienia Bluetooth")
        } else if (kind != "ble" && store.listenRequested(kind)) {
            listen(8765, {}, onStatus)
        }
        startWorker()
    }

    private fun startWorker() {
        if (started.compareAndSet(false, true)) {
            worker.scheduleWithFixedDelay({ drain() }, 0, 20, TimeUnit.SECONDS)
        }
    }
    private fun wakeQueue() {
        startWorker()
        if (!closed) runCatching { worker.execute { drain() } }
    }

    fun configurePeers(text: String) {
        require(kind == "tcp") { "BLE wykrywa sąsiadów automatycznie" }
        val next = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(next.size <= 8) { "Maksymalnie 8 sąsiadów" }
        require(next.all { address ->
            val cut = address.lastIndexOf(':')
            cut > 0 && address.substring(0, cut).none { it.isWhitespace() } &&
                address.substring(cut + 1).toIntOrNull() in 1..65535
        }) { "Podaj adresy host:port po przecinku" }
        store.configurePeers(kind, next)
        changed()
        wakeQueue()
    }

    fun joinNetwork() {
        check(kind == "ble" && transport is BleMeshTransport)
        store.setAutoMesh(true)
        listen(8765, {}, status)
        transport.startDiscovery({ peer ->
            try {
                store.discoverPeer(peer)
                changed()
                wakeQueue()
            } catch (e: Exception) { status("Błąd zapisu wykrytego węzła: ${e.message}") }
        }, status)
        changed()
    }

    fun leaveNetwork() {
        if (kind == "ble") store.setAutoMesh(false)
        store.requestListen(kind, false)
        transport.close()
        status("Odłączono. Zapisane SOS pozostają w kolejce do ponownego dołączenia.")
        changed()
    }

    fun publishReport(report: Codec.Report, note: String): StoredReport {
        val item = store.addAndEnqueue(report, note, kind)
        status("SOS zapisano w kolejce. Brak potwierdzenia przyjęcia pomocy przez człowieka.")
        changed()
        wakeQueue()
        return item
    }

    fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit) {
        received = onPacket
        status = onStatus
        store.requestListen(kind, true)
        transport.listen(port, { bytes ->
            try {
                val message = MeshMessage.decode(bytes)
                val fresh = store.accept(message, kind) // COMMIT, including any forwarding queue, before ACK.
                if (fresh) {
                    runCatching { received(message.payload) }
                    status("Zapisano ${String(message.payload, Charsets.US_ASCII)}")
                } else status("Potwierdzono wcześniej przyjęty UUID ${message.id}")
                changed()
                wakeQueue()
            } catch (e: Exception) {
                status("Odrzucono wiadomość: ${e.message}")
                throw e // A rejected message must not receive a persistence receipt.
            }
        }, onStatus)
        startWorker()
    }


    /** New user action = new UUID; every automatic retry reuses the stored frame and UUID. */
    fun publish(payload: ByteArray, maxHops: Int = 4, reportId: String? = null): UUID {
        val id = store.enqueue(payload, kind, maxHops, reportId)
        status("Raport w trwałej kolejce: $id")
        changed()
        wakeQueue()
        return id
    }


    private fun drain() {
        if (closed || !busy.compareAndSet(false, true)) return
        try {
            if (store.expireForwarding() > 0) changed()
            if (!canSend() || (kind == "ble" && !store.autoMeshEnabled())) {
                busy.set(false)
                return
            }
            val item = store.nextPending(kind, (transport as? BleMeshTransport)?.reachablePeers())
            if (item == null) {
                busy.set(false)
                return
            }
            transport.send(item.peer, item.frame) { result ->
                if (closed) { busy.set(false); return@send }
                try {
                    store.recordAttempt(item, result)
                    status(if (result.isSuccess) "Zapis u sąsiada potwierdzony: ${item.peer}"
                        else "W kolejce: ${item.peer} · ${result.exceptionOrNull()?.message}")
                    changed()
                } catch (e: Exception) {
                    status("Błąd zapisu stanu kolejki: ${e.message}")
                } finally { busy.set(false) }
                if (result.isSuccess) wakeQueue()
            }
        } catch (e: Exception) {
            busy.set(false)
            status("Błąd kolejki: ${e.message}")
        }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        transport.close()
    }
}
