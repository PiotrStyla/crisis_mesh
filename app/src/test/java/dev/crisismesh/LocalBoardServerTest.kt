package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

class LocalBoardServerTest {
    private fun row(id: String) = StoredReport(id, 1234, "mesh", Codec.validate(3, 1, "fire", "rescue"), "SECRET-NOTE-9821")
    private fun request(port: Int, method: String = "GET", path: String = "/"): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        return try {
            val status = connection.responseCode
            val body = (if (status >= 400) connection.errorStream else connection.inputStream).use { it.readBytes() }
            assertEquals(body.size, connection.contentLength)
            status to body.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    @Test fun boardEscapesUntrustedIdsAndNeverDisclosesPrivateNotes() {
        LocalBoardServer { listOf(row("<script>alert('x')</script>")) }.use { server ->
            val (status, html) = request(server.start(0))
            assertEquals(200, status)
            assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
            assertFalse(html.contains("<script>"))
            assertFalse(html.contains("SECRET-NOTE-9821"))
            assertTrue(html.contains("pożar"))
            assertTrue(html.contains("Osoby łącznie: 3; w tym dzieci: 1"))
        }
    }

    @Test fun boardReadsCurrentPublishedStateAndRejectsWrites() {
        val rows = AtomicReference(listOf(row("report-before")))
        LocalBoardServer { rows.get() }.use { server ->
            val port = server.start(0)
            assertTrue(request(port).second.contains("report-before"))
            rows.set(listOf(row("report-after")))
            val after = request(port).second
            assertTrue(after.contains("report-after"))
            assertFalse(after.contains("report-before"))
            assertEquals(405, request(port, "POST").first)
            assertEquals(404, request(port, path = "/missing").first)
            assertEquals(200, request(port, path = "/health").first)
        }
    }

    @Test fun boardCapsOutputAndReleasesItsPortForRestart() {
        val server = LocalBoardServer { (0..100).map { row("unique-report-$it-end") } }
        val port = server.start(0)
        try {
            val html = request(port).second
            assertTrue(html.contains("unique-report-99-end"))
            assertFalse(html.contains("unique-report-100-end"))
            server.close()
            assertEquals(port, server.start(port))
            assertEquals(200, request(port).first)
        } finally { server.close() }
    }
}
