// MeshTransport.kt - seam between CM1 payloads and the byte-moving link.
// UI and codec only see send/listen/close; the TCP implementation is the
// first adapter (LAN / emulator loopback). A BLE or WiFi Aware implementation
// can replace it without touching codec.py semantics or the screens.

package dev.crisismesh

interface MeshTransport {
    /**
     * Accept inbound frames on [port] until [close]. [onPacket] receives the
     * exact payload bytes of every frame; validation belongs to the caller.
     * [onStatus] may be invoked from background threads.
     */
    fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit)

    /** Fire-and-forget send of [payload] to "host:port", from a worker thread. */
    fun send(address: String, payload: ByteArray, onStatus: (String) -> Unit)

    /** Stop listening; in-flight sends finish on their own. */
    fun close()
}

/**
 * One TCP connection per packet, 4-byte big-endian length frame. Simple enough
 * to audit: no discovery, no persistence, no reconnect state to go stale.
 * Emulators reach each other through adb reverse mappings on loopback.
 */
class TcpMeshTransport : MeshTransport {
    private object Frame {
        const val MAX = 1024
    }

    @Volatile private var server: java.net.ServerSocket? = null
    @Volatile private var closed = false

    override fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit) {
        close()
        closed = false
        Thread({
            try {
                val srv = java.net.ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
                }
                server = srv
                onStatus("Nasłuch na porcie $port")
                while (!closed) {
                    try {
                        srv.accept().use { sock ->
                            sock.soTimeout = 10_000
                            val input = java.io.DataInputStream(sock.getInputStream())
                            val length = input.readInt()
                            if (length <= 0 || length > Frame.MAX) {
                                onStatus("Odrzucono ramkę o długości $length")
                                return@use
                            }
                            val payload = ByteArray(length)
                            input.readFully(payload)
                            onPacket(payload)
                        }
                    } catch (e: java.net.SocketException) {
                        if (closed) break else onStatus("Błąd gniazda: ${e.message}")
                    } catch (e: Exception) {
                        onStatus("Błąd odbioru: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!closed) onStatus("Nie mogę nasłuchiwać na porcie $port: ${e.message}")
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
                }
                onStatus("Wysłano ${payload.size} B → $host:$port")
            } catch (e: Exception) {
                onStatus("Błąd wysyłki do $host:$port: ${e.message}")
            }
        }, "mesh-send").apply { isDaemon = true; start() }
    }

    override fun close() {
        closed = true
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
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
