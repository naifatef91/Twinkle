package app.twinkle.web

import android.content.ContextWrapper
import app.twinkle.data.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Downloads have their own port so a browser's per-host connection limit cannot starve the page's status checks
 * (which made the page say "disconnected" while files were still arriving). The page learns the port from
 * /api/status; the second port runs the same handler with the same checks.
 */
class DownloadPortTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }
    private val port = freePort()
    private val dlPort = freePort()
    private var server: HttpServer? = null

    @After fun stop() { server?.stop() }

    private fun start(withDownloadPort: Boolean): HttpServer =
        HttpServer(context = object : ContextWrapper(null) {}, filesProvider = { emptyList<PickedFile>() }, onUploaded = { _, _ -> })
            .also { it.start(port, if (withDownloadPort) dlPort else null); server = it }

    private fun get(p: Int, path: String, host: String = "127.0.0.1:$p"): Pair<Int, String> =
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", p), 3_000)
            s.soTimeout = 10_000
            s.getOutputStream().apply { write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            val all = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            all.substringBefore("\r\n").split(' ')[1].toInt() to all.substringAfter("\r\n\r\n", "")
        }

    @Test fun statusTellsThePageWhichPortServesDownloads() {
        start(withDownloadPort = true)
        val (code, body) = get(port, "/api/status")
        assertEquals(200, code)
        assertTrue(body, body.contains("\"dl\":$dlPort"))
    }

    @Test fun theDownloadPortRunsTheSameHandlerAndChecks() {
        start(withDownloadPort = true)
        assertEquals(404, get(dlPort, "/d/99").first)
        assertEquals("DNS-rebinding guard applies there too", 400, get(dlPort, "/d/1", host = "evil.example").first)
    }

    @Test fun withoutASecondPortThePageKeepsItsOwn() {
        start(withDownloadPort = false)
        val (_, body) = get(port, "/api/status")
        assertTrue(body, body.contains("\"dl\":0"))
    }
}
