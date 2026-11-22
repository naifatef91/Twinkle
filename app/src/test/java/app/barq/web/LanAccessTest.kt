package app.barq.web

import android.content.ContextWrapper
import app.barq.data.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sharing on the phone's own Wi-Fi/Ethernet network: connections there are closed until the owner opens them,
 * and then nothing but the page itself is served without the PIN. The real server runs on loopback with every
 * connection treated as arriving on the shared network.
 */
class LanAccessTest {
    private val port: Int = ServerSocket(0).use { it.localPort }
    private val exhausted = AtomicInteger(0)
    private val server = HttpServer(
        context = object : ContextWrapper(null) {},
        filesProvider = { emptyList<PickedFile>() },
        onUploaded = { _, _ -> },
        phoneName = { "Test" },
        onPinExhausted = { exhausted.incrementAndGet() },
        linkOf = { HttpServer.Link.LAN },
    ).also { it.start(port) }

    @After fun stop() = server.stop()

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun raw(request: String): Reply {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            sock.soTimeout = 10_000
            sock.getOutputStream().apply { write(request.toByteArray(Charsets.UTF_8)); flush() }
            val all = sock.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            if (all.isEmpty()) throw IOException("closed without a reply")
            val lines = all.substringBefore("\r\n\r\n").split("\r\n")
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Reply(lines[0].split(' ')[1].toInt(), headers, all.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun get(path: String, cookie: String? = null): Reply =
        raw("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" + (cookie?.let { "Cookie: $it\r\n" } ?: "") + "Connection: close\r\n\r\n")

    private fun login(pin: String, gate: Boolean = true): Reply {
        val body = """{"pin":"$pin"}"""
        return raw(
            "POST /api/login HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" + (if (gate) "X-Wamda-Upload: 1\r\n" else "") +
                "Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
        )
    }

    private fun refused(block: () -> Unit): Boolean = try { block(); false } catch (e: IOException) { true }

    @Test fun theSharedNetworkIsClosedUntilTheOwnerOpensIt() {
        assertTrue("closed LAN must not answer at all", refused { get("/api/status") })
        server.openLan("123456")
        assertEquals(401, get("/api/status").status)
        server.closeLan()
        assertTrue(refused { get("/api/status") })
    }

    @Test fun withoutThePinOnlyThePageItselfIsServed() {
        server.openLan("123456")
        for (path in listOf("/api/status", "/api/files", "/d/1", "/upload/status/x")) {
            assertEquals("$path without PIN", 401, get(path).status)
        }
        assertEquals(401, get("/api/status", cookie = "wamda_s=" + "a".repeat(64)).status)
    }

    @Test fun theRightPinGivesACookieThatOpensTheApi() {
        server.openLan("482913")
        assertEquals(403, login("000000").status)
        val ok = login("482913")
        assertEquals(200, ok.status)
        val setCookie = ok.headers["set-cookie"]
        assertNotNull(setCookie)
        assertTrue(setCookie!!.contains("HttpOnly") && setCookie.contains("SameSite=Strict"))
        val cookie = setCookie.substringBefore(';')
        assertEquals(200, get("/api/status", cookie).status)
        assertEquals(200, get("/api/files", cookie).status)

        // A new PIN signs every browser out.
        server.openLan("111111")
        assertEquals(401, get("/api/status", cookie).status)
    }

    @Test fun loginNeedsTheCustomHeaderSoAnotherSiteCannotGuess() {
        server.openLan("123456")
        assertEquals(400, login("123456", gate = false).status)
    }

    @Test fun guessingIsLimitedPerDeviceAndThenThePinIsBurnt() {
        server.openLan("123456")
        repeat(5) { assertEquals(403, login("00000$it").status) }
        // Sixth try from the same address waits, even with the right PIN.
        assertEquals(429, login("123456").status)
        assertEquals(0, exhausted.get())
    }

    @Test fun interfacesAreSortedIntoTheRightLinks() {
        assertEquals(HttpServer.Link.TRUSTED, HttpServer.linkOfInterface("p2p-wlan0-0"))
        assertEquals(HttpServer.Link.TRUSTED, HttpServer.linkOfInterface("rndis0"))
        assertEquals(HttpServer.Link.LAN, HttpServer.linkOfInterface("wlan0"))
        assertEquals(HttpServer.Link.LAN, HttpServer.linkOfInterface("eth0"))
        assertEquals(HttpServer.Link.REFUSED, HttpServer.linkOfInterface("rmnet_data0"))
        assertEquals(HttpServer.Link.REFUSED, HttpServer.linkOfInterface("tun0"))
        assertTrue(HttpServer.newPin().matches(Regex("\\d{6}")))
    }

    @Test fun pinAndCookieStayAsciiUnderAnArabicLocale() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        try {
            repeat(20) { assertTrue(HttpServer.newPin().matches(Regex("[0-9]{6}"))) }
            server.openLan("123456")
            val cookie = login("123456").headers["set-cookie"]!!.substringBefore(';')
            assertTrue(cookie, cookie.matches(Regex("wamda_s=[0-9a-f]{64}")))
            assertEquals(200, get("/api/status", cookie).status)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
