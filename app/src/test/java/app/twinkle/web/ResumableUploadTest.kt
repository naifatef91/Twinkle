package app.twinkle.web

import android.content.ContextWrapper
import app.twinkle.data.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Browser uploads in PC mode survive a dropped connection: the file goes up in chunks at explicit offsets, the page
 * asks the phone how much it already holds, and continues from there. Runs the real HTTP server on loopback against
 * a temp-directory store.
 */
class ResumableUploadTest {
    private val dir: File = Files.createTempDirectory("twinkle-upl").toFile()
    private val port: Int = ServerSocket(0).use { it.localPort }
    private var server: HttpServer? = null
    private val uploaded = CopyOnWriteArrayList<String>()
    private val store = DirStore(dir)

    /** UploadStore over files in [root]; remembers what was discarded. */
    private class DirStore(private val root: File) : UploadStore {
        val discarded = CopyOnWriteArrayList<String>()
        val finished = CopyOnWriteArrayList<String>()
        private val names = HashMap<String, String>()
        @Synchronized override fun create(name: String, mime: String): String {
            val token = "t${names.size}"
            names[token] = name
            File(root, token).createNewFile()
            return token
        }
        override fun writeAt(token: String, offset: Long, data: InputStream, length: Long): Long {
            RandomAccessFile(File(root, token), "rw").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(16 * 1024); var left = length; var done = 0L
                while (left > 0) {
                    val n = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    raf.write(buf, 0, n); left -= n; done += n
                }
                return done
            }
        }
        override fun finish(token: String, fallbackName: String): String { finished += token; return names[token] ?: fallbackName }
        override fun discard(token: String) { discarded += token; File(root, token).delete() }
        fun bytes(token: String): ByteArray = File(root, token).readBytes()
    }

    @Before fun start() {
        val s = HttpServer(
            context = object : ContextWrapper(null) {},
            filesProvider = { emptyList<PickedFile>() },
            onUploaded = { n, _ -> uploaded += n },
            uploadStore = store,
        )
        s.start(port); server = s
    }
    @After fun stop() { server?.stop(); dir.deleteRecursively() }

    private class Reply(val status: Int, val body: String)

    private val host get() = "127.0.0.1:$port"
    private val gate get() = "X-Twinkle-Upload: 1\r\nOrigin: http://$host\r\n"

    private fun call(method: String, path: String, body: ByteArray = ByteArray(0), headers: String = gate, hostHeader: String = host, declaredLength: Int = body.size, closeEarly: Boolean = false): Reply {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            sock.soTimeout = 10_000
            val head = "$method $path HTTP/1.1\r\nHost: $hostHeader\r\n$headers" +
                (if (method == "POST") "Content-Length: $declaredLength\r\n" else "") + "Connection: close\r\n\r\n"
            sock.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
            if (closeEarly) { sock.shutdownOutput(); sock.close(); return Reply(0, "") }
            val all = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
            return Reply(all.lineSequence().first().split(' ')[1].toInt(), all.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun field(json: String, name: String): String = Regex("\"$name\":\\s*(\"([^\"]*)\"|(-?\\d+))").find(json)!!.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
    private fun init(name: String, size: Long, key: String = "k-$name-$size"): Reply =
        call("POST", "/upload/init", "{\"name\":\"$name\",\"size\":$size,\"key\":\"$key\"}".toByteArray(), headers = gate + "Content-Type: application/json\r\n")

    private fun data(n: Int, seed: Long = 7) = ByteArray(n).also { Random(seed).nextBytes(it) }

    @Test fun aFileSentInChunksArrivesIntactAndIsReportedOnce() {
        val bytes = data(2_500)
        val i = init("report.bin", bytes.size.toLong())
        assertEquals("init said: ${i.body}", 200, i.status)
        val id = field(i.body, "id"); assertEquals("0", field(i.body, "offset"))
        var off = 0
        while (off < bytes.size) {
            val len = minOf(1_000, bytes.size - off)
            val r = call("POST", "/upload/chunk/$id/$off", bytes.copyOfRange(off, off + len))
            assertEquals(200, r.status); off += len
            assertEquals(off.toString(), field(r.body, "offset"))
        }
        val f = call("POST", "/upload/finish/$id", "{}".toByteArray())
        assertEquals(200, f.status)
        assertTrue(f.body.contains("report.bin"))
        assertEquals(listOf("report.bin"), uploaded.toList())
        assertTrue(bytes.contentEquals(store.bytes("t0")))
        assertEquals(1, store.finished.size)
    }

    @Test fun aConnectionCutMidChunkLosesOnlyThatChunkAndTheUploadContinues() {
        val bytes = data(3_000)
        val id = field(init("movie.bin", 3_000).body, "id")
        assertEquals(200, call("POST", "/upload/chunk/$id/0", bytes.copyOfRange(0, 1_000)).status)

        // The cable is pulled halfway through the second chunk: 1,000 announced, 400 delivered, then gone.
        call("POST", "/upload/chunk/$id/1000", bytes.copyOfRange(1_000, 1_400), declaredLength = 1_000, closeEarly = true)
        Thread.sleep(500)

        // After reconnecting the page asks where the phone is: still at 1,000, nothing half-written is counted.
        val st = call("GET", "/upload/status/$id")
        assertEquals(200, st.status); assertEquals("1000", field(st.body, "offset"))

        // It carries on from there, no restart.
        assertEquals(200, call("POST", "/upload/chunk/$id/1000", bytes.copyOfRange(1_000, 2_000)).status)
        assertEquals(200, call("POST", "/upload/chunk/$id/2000", bytes.copyOfRange(2_000, 3_000)).status)
        assertEquals(200, call("POST", "/upload/finish/$id", "{}".toByteArray()).status)
        assertTrue("the file must equal the original byte for byte", bytes.contentEquals(store.bytes("t0")))
    }

    @Test fun reopeningTheSameFileContinuesTheSameUpload() {
        // Page reloaded, or the same file dropped again: same key, so the phone answers with the same upload and offset.
        val bytes = data(2_000)
        val a = init("big.bin", 2_000, key = "same")
        val id = field(a.body, "id")
        call("POST", "/upload/chunk/$id/0", bytes.copyOfRange(0, 1_000))
        val b = init("big.bin", 2_000, key = "same")
        assertEquals(id, field(b.body, "id"))
        assertEquals("1000", field(b.body, "offset"))
        assertEquals("only one hidden entry exists", 1, File(dir, "t0").let { if (it.exists()) 1 else 0 })
        assertFalse(File(dir, "t1").exists())
    }

    @Test fun aWrongOffsetIsAnsweredWithTheRealOne() {
        val id = field(init("a.bin", 2_000).body, "id")
        call("POST", "/upload/chunk/$id/0", data(1_000))
        val r = call("POST", "/upload/chunk/$id/0", data(1_000)) // resend of a chunk the phone already holds
        assertEquals(409, r.status); assertEquals("1000", field(r.body, "offset"))
        val r2 = call("POST", "/upload/chunk/$id/1500", data(500)) // a gap
        assertEquals(409, r2.status); assertEquals("1000", field(r2.body, "offset"))
    }

    @Test fun badRangesAndEarlyFinishAreRefused() {
        val id = field(init("a.bin", 1_000).body, "id")
        assertEquals(400, call("POST", "/upload/chunk/$id/0", data(1_001)).status)           // longer than the file
        assertEquals(400, call("POST", "/upload/chunk/$id/-5", data(10)).status)             // negative
        assertEquals(400, call("POST", "/upload/chunk/$id/abc", data(10)).status)            // not a number
        assertEquals(409, call("POST", "/upload/finish/$id", "{}".toByteArray()).status)     // nothing uploaded yet
        assertEquals(404, call("POST", "/upload/chunk/nope/0", data(10)).status)             // unknown upload
        assertEquals(400, init("x.bin", -1).status)                                          // impossible size
        assertTrue(uploaded.isEmpty())
    }

    @Test fun arabicNamesSurviveAndAnEmptyFileCanBeFinished() {
        val i = init("تقرير ٢٠٢٦ 🌴.txt", 0)
        assertEquals(200, i.status)
        val id = field(i.body, "id")
        val f = call("POST", "/upload/finish/$id", "{}".toByteArray())
        assertEquals(200, f.status)
        assertEquals(listOf("تقرير ٢٠٢٦ 🌴.txt"), uploaded.toList())
    }

    // ---- the CSRF and rebinding rules apply to every new route, not just the old one ----

    @Test fun everyUploadRouteNeedsTheCustomHeader() {
        val id = field(init("a.bin", 100).body, "id")
        val none = "" // no X-Twinkle-Upload
        assertEquals(400, call("POST", "/upload/init", "{\"name\":\"a\",\"size\":1}".toByteArray(), headers = none).status)
        assertEquals(400, call("POST", "/upload/chunk/$id/0", data(10), headers = none).status)
        assertEquals(400, call("POST", "/upload/finish/$id", "{}".toByteArray(), headers = none).status)
        assertEquals(400, call("GET", "/upload/status/$id", headers = none).status)
    }

    @Test fun everyUploadRouteRefusesAnotherOriginAndADnsHost() {
        val id = field(init("a.bin", 100).body, "id")
        val evil = "X-Twinkle-Upload: 1\r\nOrigin: http://evil.example\r\n"
        assertEquals(400, call("POST", "/upload/init", "{\"name\":\"a\",\"size\":1}".toByteArray(), headers = evil).status)
        assertEquals(400, call("POST", "/upload/chunk/$id/0", data(10), headers = evil).status)
        assertEquals(400, call("POST", "/upload/finish/$id", "{}".toByteArray(), headers = evil).status)
        assertEquals(400, call("GET", "/upload/status/$id", headers = evil).status)
        // DNS rebinding: a name in Host is refused before anything else is looked at.
        assertEquals(400, call("GET", "/upload/status/$id", hostHeader = "evil.example").status)
        assertEquals(400, call("POST", "/upload/chunk/$id/0", data(10), hostHeader = "evil.example:$port").status)
    }

    @Test fun stoppingTheServerDiscardsUnfinishedUploads() {
        val id = field(init("half.bin", 2_000).body, "id")
        call("POST", "/upload/chunk/$id/0", data(1_000))
        server!!.stop(); server = null
        assertEquals(listOf("t0"), store.discarded.toList())
        assertFalse(File(dir, "t0").exists())
    }
}
