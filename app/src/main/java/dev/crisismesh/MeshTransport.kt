// MeshTransport.kt - seam between CM1 payloads and the byte-moving link.
// UI and codec only see send/listen/close. Two adapters implement it:
// TcpMeshTransport (LAN / emulator loopback) and BleMeshTransport (radio).
// Neither touches codec.py semantics or the screens.

package dev.crisismesh

/** A BLE peer address: six hex octets separated by colons. */
internal val MAC_ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

interface MeshTransport {
    /**
     * Accept inbound frames on [port] until [close]. [onPacket] receives the
     * exact payload bytes of every frame; validation belongs to the caller.
     * [onStatus] may be invoked from background threads.
     */
    fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit)

    /** Worker-thread send; waits for frame receipt, not durable storage or end-to-end delivery. */
    fun send(address: String, payload: ByteArray, onStatus: (String) -> Unit)

    /** Stop listening; in-flight sends finish on their own. */
    fun close()
}

/**
 * One TCP connection per packet, 4-byte big-endian length frame. Simple enough
 * to audit: no discovery, no persistence, no reconnect state to go stale.
 * Emulators reach each other through adb forward mappings on loopback.
 */
class TcpMeshTransport : MeshTransport {
    private object Frame {
        const val MAX = 1024
        const val RECEIVED = 6
    }

    @Volatile private var server: java.net.ServerSocket? = null
    private var client: java.net.Socket? = null

    @Synchronized
    override fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit) {
        close()
        val srv = java.net.ServerSocket()
        try {
            srv.reuseAddress = true
            srv.bind(java.net.InetSocketAddress(port))
        } catch (e: Exception) {
            srv.close()
            onStatus("Nie mogę nasłuchiwać na porcie $port: ${e.message}")
            return
        }
        server = srv
        Thread({
            onStatus("Nasłuch na porcie ${srv.localPort}")
            while (!srv.isClosed) {
                try {
                    srv.accept().use { sock ->
                        synchronized(this) {
                            if (server !== srv) return@use
                            client = sock
                        }
                        try {
                            sock.soTimeout = 10_000
                            val input = java.io.DataInputStream(sock.getInputStream())
                            val length = input.readInt()
                            require(length in 1..Frame.MAX) { "Niepoprawna długość ramki: $length" }
                            val payload = ByteArray(length)
                            input.readFully(payload)
                            synchronized(this) {
                                if (server !== srv) return@use
                                onPacket(payload)
                                sock.getOutputStream().apply { write(Frame.RECEIVED); flush() }
                            }
                            // Keep the receipt readable until the sender closes. ADB tunnels
                            // can discard buffered output when either endpoint closes early.
                            check(input.read() == -1) { "Dane po zakończeniu ramki" }
                        } finally {
                            synchronized(this) { if (client === sock) client = null }
                        }
                    }
                } catch (e: Exception) {
                    if (!srv.isClosed) onStatus("Błąd odbioru: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }, "mesh-listen").apply { isDaemon = true; start() }
    }

    override fun send(address: String, payload: ByteArray, onStatus: (String) -> Unit) {
        val parsed = parse(address)
        if (parsed == null) {
            onStatus("Podaj adres rozmówcy jako host:port")
            return
        }
        val (host, port) = parsed
        Thread({
            try {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(host, port), 3000)
                    sock.soTimeout = 10_000
                    val out = java.io.DataOutputStream(sock.getOutputStream())
                    out.writeInt(payload.size)
                    out.write(payload)
                    out.flush()
                    check(sock.getInputStream().read() == Frame.RECEIVED) { "Brak potwierdzenia ramki" }
                }
                onStatus("Ramka odebrana przez $host:$port · ${payload.size} B (nie potwierdza zapisu)")
            } catch (e: Exception) {
                onStatus("Błąd wysyłki do $host:$port: ${e.message}")
            }
        }, "mesh-send").apply { isDaemon = true; start() }
    }

    @Synchronized
    override fun close() {
        server?.close()
        server = null
        client?.close()
        client = null
    }

    private fun parse(address: String): Pair<String, Int>? {
        val cut = address.lastIndexOf(':')
        if (cut <= 0 || cut == address.length - 1) return null
        val host = address.substring(0, cut).trim()
        val port = address.substring(cut + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }
}
