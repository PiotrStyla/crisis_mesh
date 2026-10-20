package dev.crisismesh

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Explicitly enabled, read-only public board. It is not a captive portal or rescue dispatch. */
class LocalBoardServer(private val reports: () -> List<StoredReport>) : AutoCloseable {
    private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var acceptThread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized fun start(port: Int = 8080): Int {
        listener?.let { return it.localPort }
        val server = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(port)) }
        val pool = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { runnable -> Thread(runnable, "local-board-client").apply { isDaemon = true } })
        listener = server
        workers = pool
        acceptThread = Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                clients.add(client)
                try { pool.execute { serve(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
            }
        }, "local-board-http").apply { isDaemon = true; start() }
        return server.localPort
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val header = StringBuilder()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!header.endsWith("\r\n\r\n")) {
                    if (header.length >= 8192 || System.nanoTime() > deadline) return
                    val byte = input.read()
                    if (byte < 0) return
                    header.append(byte.toChar())
                }
                val request = header.toString().substringBefore("\r\n").split(' ')
                if (request.size != 3 || (request[2] != "HTTP/1.0" && request[2] != "HTTP/1.1")) {
                    respond(socket, 400, "Bad Request", "Nieprawidłowe żądanie", "text/plain"); return
                }
                if (request[0] != "GET") {
                    respond(socket, 405, "Method Not Allowed", "Tablica jest tylko do odczytu.", "text/plain", "Allow: GET\r\n"); return
                }
                when (request[1].substringBefore('?')) {
                    "/" -> respond(socket, 200, "OK", page(reports()), "text/html")
                    "/health" -> respond(socket, 200, "OK", "{\"status\":\"ok\",\"readOnly\":true}", "application/json")
                    else -> respond(socket, 404, "Not Found", "Nie znaleziono", "text/plain")
                }
            }
        } catch (_: Exception) {
            // Disconnect, timeout or a closed store: never return private exception details to LAN clients.
        } finally { clients.remove(client); runCatching { client.close() } }
    }

    private fun respond(socket: Socket, code: Int, reason: String, text: String, type: String, extra: String = "") {
        val body = text.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    private fun page(rows: List<StoredReport>): String = buildString {
        append("<!doctype html><html lang=\"pl\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Crisis Mesh — lokalna tablica</title>")
        append("<style>body{font:18px system-ui;max-width:760px;margin:24px auto;padding:0 18px;background:#101318;color:#f3f4f6}a{color:#ffd070}article{border:1px solid #606975;border-radius:12px;padding:16px;margin:16px 0}small{overflow-wrap:anywhere;color:#b7c0cd}.warning{border-left:5px solid #ffd070;padding-left:14px}h1{font-size:28px}</style>")
        append("<h1>Crisis Mesh</h1><p>Lokalna tablica — bez konta i internetu.</p><p class=\"warning\">Niezweryfikowane zgłoszenia użytkowników. To nie są oficjalne alarmy ani potwierdzenia przyjęcia pomocy przez służby. W bezpośrednim zagrożeniu życia, gdy masz zasięg, dzwoń pod 112.</p>")
        append("<p><a href=\"/\">Odśwież zgłoszenia</a> · widoczne maksymalnie 100 ostatnich opublikowanych raportów. Strona nie odświeża się automatycznie.</p>")
        if (rows.isEmpty()) append("<p>Brak opublikowanych zgłoszeń na tym węźle. Nie oznacza to braku zagrożeń w okolicy.</p>")
        val dateFormat = DateFormat.getDateTimeInstance()
        for (index in 0 until minOf(rows.size, 100)) {
            val row = rows[index]
            append("<article><h2>Zgłoszenie — niezweryfikowane</h2><p>")
            append(escape(Codec.render(row.report)))
            append("</p><small>Czas zapisany na tym urządzeniu: ")
            append(escape(dateFormat.format(Date(row.createdAtMillis))))
            append("<br>Identyfikator raportu: "); append(escape(row.id)); append("</small></article>")
        }
        append("<footer><p>Połączenie lokalne HTTP nie jest szyfrowane. Nie podawaj tutaj danych wrażliwych. Ta tablica nie przyjmuje wpisów i nie udostępnia prywatnych notatek.</p></footer></html>")
    }

    @Synchronized override fun close() {
        listener?.close(); listener = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers?.shutdownNow(); workers = null
        acceptThread?.join(1000); acceptThread = null
    }

    companion object {
        private fun escape(text: String): String = buildString(text.length) {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
        fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }
}
