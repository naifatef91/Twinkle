package app.twinkle.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import app.twinkle.data.PickedFile
import app.twinkle.engine.NameSanitizer
import app.twinkle.engine.wireJson
import app.twinkle.net.IdleGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Minimal dependency-free HTTP/1.1 server for PC mode. Serves the bundled web page,
 * streams the files the phone user chose to share (with Range support), and accepts
 * browser uploads into MediaStore Downloads (Download/Twinkle). Local link only.
 *
 * Routes:
 *  GET  /            -> assets/pc/index.html
 *  GET  /pc/<asset>  -> other files under assets/pc/
 *  GET  /api/files   -> {"files":[{id,name,size,mime}]}
 *  GET  /d/<id>      -> file download (Range aware)
 *  POST /upload      -> multipart/form-data "file" parts -> {"saved":[names]}
 *  POST /api/login   -> {"pin":"123456"} -> session cookie (same-network links only)
 *
 * Links: the Wi-Fi Direct group and USB tethering belong to PC mode and need no sign-in. The phone's
 * own Wi-Fi or Ethernet network (shared with every other device on the router) is closed unless
 * [openLan] was called, and even then every route that touches files needs the PIN first.
 */
class HttpServer(
    private val context: Context,
    private val filesProvider: () -> List<PickedFile>,
    private val onUploaded: (name: String, size: Long) -> Unit,
    private val phoneName: () -> String = { "Twinkle" },
    private val uploadedNames: () -> List<String> = { emptyList() },
    uploadStore: UploadStore? = null,
    /** Called when too many wrong PINs were tried: the caller should pick a new PIN and call [openLan] again. */
    private val onPinExhausted: () -> Unit = {},
    /**
     * Interface prefixes this session serves without sign-in: the Wi-Fi Direct group for Wi-Fi mode, USB tethering
     * for cable mode, none for same-network mode. The other PC-mode link is refused outright.
     */
    private val trustedLinks: List<String> = ALLOWED_IFACES,
    /** Test hook: decides which kind of link a connection arrived on. */
    private val linkOf: ((Socket) -> Link)? = null,
) {
    /** What a connection arrived on. */
    enum class Link {
        /** Wi-Fi Direct group, USB tethering, loopback: owned by PC mode, no sign-in. */
        TRUSTED,
        /** The phone's regular Wi-Fi or Ethernet network: only while [openLan] is on, and only with the PIN. */
        LAN,
        /** Anything else (mobile data, VPN): never answered. */
        REFUSED,
    }

    @Volatile private var lanPin: String? = null
    private val sessions = LinkedHashSet<String>()
    private val authLock = Any()
    private var failuresTotal = 0
    private val failuresByIp = HashMap<String, Pair<Int, Long>>()
    private val random = java.security.SecureRandom()

    /** Lets devices on the phone's own network in, with [pin]. Signs out every browser that used an older PIN. */
    fun openLan(pin: String) {
        synchronized(authLock) { lanPin = pin; sessions.clear(); failuresTotal = 0; failuresByIp.clear() }
    }

    /** Closes the phone's own network again and signs every browser on it out. */
    fun closeLan() {
        synchronized(authLock) { lanPin = null; sessions.clear(); failuresTotal = 0; failuresByIp.clear() }
    }

    val lanOpen: Boolean get() = lanPin != null

    @Serializable
    private data class LoginJson(val pin: String = "")

    private val store: UploadStore by lazy { uploadStore ?: MediaStoreUploads(context) }

    /** One browser upload in progress: a file sent in chunks that can be continued after the connection drops. */
    private class Upload(val id: String, val key: String, val token: String, val name: String, val size: Long) {
        @Volatile var received = 0L
        @Volatile var touched = System.currentTimeMillis()
    }

    private val uploadsById = java.util.concurrent.ConcurrentHashMap<String, Upload>()

    @Serializable
    private data class InitJson(val name: String, val size: Long, val mime: String = "", val key: String = "")

    @Serializable
    private data class InitReply(val id: String, val offset: Long, val size: Long)

    @Serializable
    private data class OffsetReply(val offset: Long, val size: Long)
    /** [lastSeenMs] = wall-clock time of the last request; the page polls every 2 s, so < 8 s means "connected". */
    data class Stats(val bytesServed: Long = 0L, val bytesReceived: Long = 0L, val lastClientAddress: String? = null, val lastSeenMs: Long = 0L)

    @Serializable
    private data class StatusJson(val phone: String, val shared: Int, val uploaded: List<String>, val bytesServed: Long, val bytesReceived: Long)

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> get() = _stats

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    /** Shared file with the int id clients refer to; ids are stable across refreshes. */
    private data class Shared(val id: Int, val file: PickedFile)

    @Volatile private var snapshot: List<Shared> = emptyList()
    private val snapshotLock = Any()

    @Serializable
    private data class FileJson(val id: Int, val name: String, val size: Long, val mime: String)

    @Serializable
    private data class FilesJson(val files: List<FileJson>)

    @Serializable
    private data class UploadJson(val saved: List<String>)

    @Synchronized
    fun start(port: Int = PORT) {
        if (serverSocket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port)) // all interfaces
        serverSocket = ss
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            while (true) {
                val client = try { ss.accept() } catch (e: IOException) { break }
                sc.launch { serve(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        // Unfinished uploads must not stay behind as hidden files once the server is gone.
        uploadsById.values.forEach { runCatching { store.discard(it.token) } }
        uploadsById.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    // ---------- connection loop ----------

    private class BadRequest(msg: String) : Exception(msg)

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: LimitedInputStream)

    private class Response(
        val status: Int,
        val headers: MutableMap<String, String> = LinkedHashMap(),
        val length: Long,
        val write: (OutputStream) -> Unit,
    )

    private fun serve(socket: Socket) {
        // The links PC mode owns (Wi-Fi Direct group, USB tethering) need no sign-in. The phone's own
        // Wi-Fi/Ethernet network is shared with every device on the router, so it is closed unless the
        // user opened it, and then needs the PIN. Mobile data and VPNs are never answered.
        val link = classify(socket)
        if (link == Link.REFUSED || (link == Link.LAN && lanPin == null)) {
            Log.w(TAG, "refused ${socket.inetAddress?.hostAddress} on ${socket.localAddress?.hostAddress}: $link link not open")
            runCatching { socket.close() }
            return
        }
        if (open.incrementAndGet() > MAX_CONNECTIONS) { open.decrementAndGet(); runCatching { socket.close() }; return }
        try { serveChecked(socket, link == Link.LAN) } finally { open.decrementAndGet() }
    }

    private val open = java.util.concurrent.atomic.AtomicInteger(0)

    private fun classify(socket: Socket): Link {
        linkOf?.let { return it(socket) }
        val local = socket.localAddress ?: return Link.REFUSED
        if (local.isLoopbackAddress) return Link.TRUSTED
        val name = runCatching { java.net.NetworkInterface.getByInetAddress(local)?.name }.getOrNull() ?: return Link.REFUSED
        return linkOfInterface(name, trustedLinks)
    }

    private fun serveChecked(socket: Socket, lan: Boolean) {
        socket.soTimeout = 60_000
        socket.tcpNoDelay = true
        // A connection that has not delivered its first complete request line and headers within
        // the deadline is closed, so idle sockets cannot exhaust the connection slots.
        val firstRequest = IdleGuard.arm(socket)
        val client = socket.inetAddress?.hostAddress ?: ""
        try {
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            var keepAlive = true
            while (keepAlive) {
                val req = try { readRequest(input) } finally { firstRequest.disarm() } ?: break
                keepAlive = !req.headers["connection"].equals("close", ignoreCase = true)
                val resp = try {
                    route(req, lan, client)
                } catch (e: BadRequest) {
                    text(400, e.message ?: "Bad request")
                } catch (e: Exception) {
                    Log.w(TAG, "request failed: ${req.method} ${req.path}", e)
                    text(500, "Internal error")
                }
                if (req.body.remaining > 0) {
                    // Unread body left behind (error path). Small: drain; large: give up on keep-alive.
                    if (req.body.remaining <= 1L shl 20) req.body.drain() else keepAlive = false
                }
                writeResponse(output, resp, keepAlive, headOnly = req.method == "HEAD")
                output.flush()
            }
        } catch (_: SocketException) {
        } catch (_: SocketTimeoutException) {
        } catch (_: EOFException) {
        } catch (e: IOException) {
            Log.d(TAG, "connection closed: $e")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        var requestLine = readLine(input, 8 * 1024) ?: return null
        while (requestLine.isEmpty()) requestLine = readLine(input, 8 * 1024) ?: return null // tolerate stray CRLF
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw BadRequest("Malformed request line")
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input, 16 * 1024) ?: throw EOFException()
            if (line.isEmpty()) break
            if (++count > 100) throw BadRequest("Too many headers")
            val i = line.indexOf(':')
            if (i <= 0) continue
            headers[line.substring(0, i).trim().lowercase(Locale.US)] = line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) throw BadRequest("Chunked bodies are not supported")
        val length = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val path = target.substringBefore('?')
        return Request(method, path, headers, LimitedInputStream(input, length))
    }

    /** Reads one CRLF/LF-terminated ISO-8859-1 line (terminator stripped); null at EOF. */
    private fun readLine(input: InputStream, max: Int): String? {
        val buf = ByteArrayOutputStream(128)
        var n = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (++n > max) throw BadRequest("Header line too long")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun route(req: Request, lan: Boolean, client: String): Response {
        val get = req.method == "GET" || req.method == "HEAD"
        val path = req.path
        // DNS rebinding: the page is only ever opened by IP literal, so a DNS name in Host means a
        // foreign origin is probing the server.
        val host = hostOf(req.headers["host"]) ?: return text(400, "Bad host")
        if (!host.matches(IP_LITERAL)) return text(400, "Bad host")
        if (lan) {
            // The page itself and its assets hold nothing private; everything else needs a signed-in browser.
            val public = get && (path == "/" || path == "/index.html" || path.startsWith("/pc/"))
            if (req.method == "POST" && path == "/api/login") return login(req, client)
            if (lanPin == null) return text(403, "Closed")
            if (!public && !signedIn(req)) return jsonReply(401, """{"error":"auth"}""")
        }
        // The page polls /api/status every 2 s: that is what "connected" on the phone means.
        if (client.isNotEmpty() && path == "/api/status") _stats.update { it.copy(lastClientAddress = client, lastSeenMs = System.currentTimeMillis()) }
        return when {
            get && (path == "/" || path == "/index.html") -> asset("pc/index.html", "text/html; charset=utf-8")
            get && path.startsWith("/pc/") -> {
                val name = path.substring(4)
                if (name.matches(ASSET_NAME)) asset("pc/$name", mimeFor(name)) else text(404, "Not found")
            }
            get && path == "/api/files" -> apiFiles()
            get && path == "/api/status" -> apiStatus()
            get && path.startsWith("/d/") -> download(req, path.substring(3))
            req.method == "POST" && path == "/upload" -> upload(req)
            req.method == "POST" && path == "/upload/init" -> uploadInit(req)
            req.method == "POST" && path.startsWith("/upload/chunk/") -> uploadChunk(req, path.removePrefix("/upload/chunk/"))
            req.method == "POST" && path.startsWith("/upload/finish/") -> uploadFinish(req, path.removePrefix("/upload/finish/"))
            get && path.startsWith("/upload/status/") -> uploadStatus(req, path.removePrefix("/upload/status/"))
            req.method == "OPTIONS" -> Response(204, mutableMapOf("Allow" to "GET, HEAD, POST, OPTIONS"), 0L) {}
            (path == "/" || path == "/api/files" || path == "/upload" || path.startsWith("/d/")) -> text(405, "Method not allowed")
            else -> text(404, "Not found")
        }
    }

    // ---------- same-network sign-in ----------

    private fun cookieOf(req: Request): String? =
        req.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')

    private fun signedIn(req: Request): Boolean {
        val c = cookieOf(req)?.takeIf { it.length == 64 } ?: return false
        return synchronized(authLock) { c in sessions }
    }

    private fun login(req: Request, client: String): Response {
        requireUploadGate(req)
        if (req.body.remaining > 1024) throw BadRequest("Login body too large")
        val raw = String(req.body.readBytes(), StandardCharsets.UTF_8)
        val pin = try { wireJson.decodeFromString(LoginJson.serializer(), raw).pin } catch (e: Exception) { throw BadRequest("Bad login body") }
        var exhausted = false
        val reply: Response = synchronized(authLock) {
            val expected = lanPin ?: return@synchronized text(403, "Closed")
            val now = System.currentTimeMillis()
            val (count, since) = failuresByIp[client]?.takeIf { now - it.second < FAIL_WINDOW_MS } ?: (0 to now)
            if (count >= MAX_FAILS_PER_IP) return@synchronized jsonReply(429, """{"error":"wait"}""")
            if (java.security.MessageDigest.isEqual(pin.toByteArray(), expected.toByteArray())) {
                failuresByIp.remove(client)
                val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
                if (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.first())
                sessions += token
                jsonReply(200, """{"ok":true}""").also {
                    it.headers["Set-Cookie"] = "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200"
                }
            } else {
                failuresByIp[client] = (count + 1) to since
                if (++failuresTotal >= MAX_FAILS_TOTAL) { exhausted = true; lanPin = null; sessions.clear() }
                jsonReply(403, """{"error":"pin"}""")
            }
        }
        // Too many guesses in total: this PIN is burnt. The owner gets a fresh one (shown on the phone).
        if (exhausted) runCatching { onPinExhausted() }
        return reply
    }

    // ---------- routes ----------

    private fun asset(name: String, mime: String): Response {
        val bytes = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (e: IOException) {
            return text(404, "Not found")
        }
        return bytesResponse(200, mime, bytes).also { it.headers["Cache-Control"] = "no-cache" }
    }

    private fun refreshSnapshot(): List<Shared> {
        val files = filesProvider()
        synchronized(snapshotLock) {
            val byKey = snapshot.associateBy { it.file.key }
            var next = (snapshot.maxOfOrNull { it.id } ?: 0) + 1
            snapshot = files.map { f -> byKey[f.key] ?: Shared(next++, f) }
            return snapshot
        }
    }

    private fun apiFiles(): Response {
        val list = refreshSnapshot().map { FileJson(it.id, displayName(it.file.name), it.file.size, it.file.mime ?: mimeFor(it.file.name)) }
        val json = wireJson.encodeToString(FilesJson.serializer(), FilesJson(list))
        return bytesResponse(200, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
            .also { it.headers["Cache-Control"] = "no-store" }
    }

    private fun apiStatus(): Response {
        val s = _stats.value
        val json = wireJson.encodeToString(
            StatusJson.serializer(),
            StatusJson(phoneName(), filesProvider().size, uploadedNames().takeLast(20), s.bytesServed, s.bytesReceived),
        )
        return bytesResponse(200, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
            .also { it.headers["Cache-Control"] = "no-store" }
    }

    private fun download(req: Request, idText: String): Response {
        val id = idText.toIntOrNull() ?: return text(404, "Not found")
        val shared = refreshSnapshot().firstOrNull { it.id == id } ?: return text(404, "Not found")
        val file = shared.file
        val total = file.size.coerceAtLeast(0)

        var start = 0L
        var end = total - 1
        var partial = false
        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=", ignoreCase = true) && total > 0) {
            val spec = range.substring(6).substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return rangeNotSatisfiable(total)
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            when {
                a.isEmpty() && b.isNotEmpty() -> start = (total - (b.toLongOrNull() ?: return rangeNotSatisfiable(total))).coerceAtLeast(0)
                a.isNotEmpty() -> {
                    start = a.toLongOrNull() ?: return rangeNotSatisfiable(total)
                    if (b.isNotEmpty()) end = (b.toLongOrNull() ?: return rangeNotSatisfiable(total)).coerceAtMost(total - 1)
                }
                else -> return rangeNotSatisfiable(total)
            }
            if (start < 0 || start > end || start >= total) return rangeNotSatisfiable(total)
            partial = true
        }
        val length = if (total == 0L) 0L else end - start + 1

        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = file.mime ?: mimeFor(file.name)
        headers["Accept-Ranges"] = "bytes"
        headers["Content-Disposition"] = "attachment; filename=\"${asciiName(file.name)}\"; filename*=UTF-8''${percentEncode(displayName(file.name))}"
        headers["Cache-Control"] = "no-store"
        if (partial) headers["Content-Range"] = "bytes $start-$end/$total"

        return Response(if (partial) 206 else 200, headers, length) { out ->
            if (length > 0L) openForRead(file).use { input ->
                skipFully(input, start)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                    _stats.update { it.copy(bytesServed = it.bytesServed + n) }
                }
            }
        }
    }

    private fun rangeNotSatisfiable(total: Long): Response =
        text(416, "Range not satisfiable").also { it.headers["Content-Range"] = "bytes */$total" }

    private fun openForRead(file: PickedFile): InputStream {
        if (file.uri.scheme == "file") return FileInputStream(File(file.uri.path ?: throw IOException("bad file uri")))
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r") ?: throw IOException("cannot open ${file.uri}")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd) // closing the stream closes the descriptor
    }

    private fun skipFully(input: InputStream, count: Long) {
        if (count <= 0) return
        if (input is FileInputStream) { input.channel.position(count); return }
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw EOFException()
                left -= 1
            } else left -= s
        }
    }

    /**
     * CSRF: a plain HTML form from any site can POST to this address without a preflight; the page's own
     * uploader sets a custom header, which a cross-site form cannot. Every upload route goes through this.
     */
    private fun requireUploadGate(req: Request) {
        if (req.headers["x-twinkle-upload"] != "1") throw BadRequest("Missing upload header")
        req.headers["origin"]?.let { origin ->
            val originHost = hostOf(origin.substringAfter("://", origin))
            val reqHost = hostOf(req.headers["host"])
            if (origin != "null" && (originHost == null || originHost != reqHost)) throw BadRequest("Cross-origin upload refused")
        }
    }

    private fun jsonReply(status: Int, json: String): Response =
        bytesResponse(status, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8)).also { it.headers["Cache-Control"] = "no-store" }

    private fun uploadFor(id: String): Upload? = uploadsById[id]?.also { it.touched = System.currentTimeMillis() }

    /** Starts a chunked upload, or finds the unfinished one for the same file so the browser can continue it. */
    private fun uploadInit(req: Request): Response {
        requireUploadGate(req)
        if (req.body.remaining > 8 * 1024) throw BadRequest("Init body too large")
        val raw = ByteArrayOutputStream().also { out -> val b = ByteArray(1024); while (true) { val n = req.body.read(b); if (n < 0) break; out.write(b, 0, n) } }.toString("UTF-8")
        val init = try { wireJson.decodeFromString(InitJson.serializer(), raw) } catch (e: Exception) { throw BadRequest("Bad init body") }
        if (init.size < 0 || init.size > MAX_UPLOAD_BYTES) throw BadRequest("Bad size")
        val name = sanitizeName(init.name)
        val key = (init.key.ifBlank { "${init.name}|${init.size}" }).take(300)

        // Forget uploads nobody touched for an hour so a browser that never came back leaves no hidden file.
        val cutoff = System.currentTimeMillis() - STALE_UPLOAD_MS
        uploadsById.values.filter { it.touched < cutoff }.forEach { uploadsById.remove(it.id); runCatching { store.discard(it.token) } }

        uploadsById.values.firstOrNull { it.key == key && it.size == init.size }?.let { existing ->
            existing.touched = System.currentTimeMillis()
            return jsonReply(200, wireJson.encodeToString(InitReply.serializer(), InitReply(existing.id, existing.received, existing.size)))
        }
        if (uploadsById.size >= MAX_OPEN_UPLOADS) throw BadRequest("Too many unfinished uploads")
        val mime = init.mime.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: mimeFor(name)
        val up = Upload(java.util.UUID.randomUUID().toString(), key, store.create(name, mime), name, init.size)
        uploadsById[up.id] = up
        return jsonReply(200, wireJson.encodeToString(InitReply.serializer(), InitReply(up.id, 0L, up.size)))
    }

    /** How much of an upload the phone already holds; the browser calls this after a dropped connection. */
    private fun uploadStatus(req: Request, id: String): Response {
        requireUploadGate(req)
        val up = uploadFor(id) ?: return text(404, "Unknown upload")
        return jsonReply(200, wireJson.encodeToString(OffsetReply.serializer(), OffsetReply(up.received, up.size)))
    }

    /** Appends one chunk at [rest] = "<id>/<offset>". The offset must be exactly what the phone already has. */
    private fun uploadChunk(req: Request, rest: String): Response {
        requireUploadGate(req)
        val id = rest.substringBefore('/')
        val offset = rest.substringAfter('/', "").toLongOrNull() ?: throw BadRequest("Bad offset")
        val up = uploadFor(id) ?: return text(404, "Unknown upload")
        val len = req.headers["content-length"]?.toLongOrNull() ?: throw BadRequest("Missing Content-Length")
        if (len <= 0 || len > MAX_CHUNK_BYTES || offset < 0 || offset > up.size - len) throw BadRequest("Bad chunk range")
        synchronized(up) {
            // A retry can arrive while the previous attempt's request is still draining; the lock orders them.
            if (offset != up.received) return jsonReply(409, wireJson.encodeToString(OffsetReply.serializer(), OffsetReply(up.received, up.size)))
            val written = store.writeAt(up.token, offset, req.body, len)
            if (written != len) throw IOException("chunk cut short at $written of $len") // not committed; the browser asks for the offset and resends
            up.received = offset + len
            _stats.update { it.copy(bytesReceived = it.bytesReceived + len) }
            return jsonReply(200, wireJson.encodeToString(OffsetReply.serializer(), OffsetReply(up.received, up.size)))
        }
    }

    private fun uploadFinish(req: Request, id: String): Response {
        requireUploadGate(req)
        val up = uploadFor(id) ?: return text(404, "Unknown upload")
        synchronized(up) {
            if (up.received != up.size) return jsonReply(409, wireJson.encodeToString(OffsetReply.serializer(), OffsetReply(up.received, up.size)))
            val finalName = store.finish(up.token, up.name)
            uploadsById.remove(up.id)
            onUploaded(finalName, up.size)
            return jsonReply(200, wireJson.encodeToString(UploadJson.serializer(), UploadJson(listOf(finalName))))
        }
    }

    private fun upload(req: Request): Response {
        requireUploadGate(req)
        val ct =req.headers["content-type"] ?: throw BadRequest("Missing Content-Type")
        if (!ct.startsWith("multipart/form-data", ignoreCase = true)) throw BadRequest("Expected multipart/form-data")
        val boundary = boundaryOf(ct) ?: throw BadRequest("Missing boundary")
        val saved = ArrayList<String>()
        MultipartReader(req.body, boundary).forEachPart { headers, body ->
            val disposition = headers["content-disposition"] ?: return@forEachPart
            val rawName = dispositionParam(disposition, "filename") ?: return@forEachPart // not a file part
            val name = sanitizeName(rawName)
            val mime = headers["content-type"]?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: mimeFor(name)
            val (finalName, size) = saveToDownloads(name, mime, body)
            saved += finalName
            onUploaded(finalName, size)
        }
        val json = wireJson.encodeToString(UploadJson.serializer(), UploadJson(saved))
        return bytesResponse(200, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    /** Streams [body] into a pending MediaStore Downloads entry, then publishes it. Returns (actual name, bytes). */
    private fun saveToDownloads(name: String, mime: String, body: InputStream): Pair<String, Long> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("could not create $name")
        var total = 0L
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("could not open $name")
            out.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    it.write(buf, 0, n)
                    total += n
                    _stats.update { s -> s.copy(bytesReceived = s.bytesReceived + n) }
                }
                it.flush()
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        // MediaStore may have renamed the file ("name (1).ext"); report what it actually used.
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: name
        return actual to total
    }

    // ---------- multipart ----------

    private fun boundaryOf(contentType: String): String? {
        for (p in contentType.split(';')) {
            val kv = p.trim()
            if (kv.startsWith("boundary=", ignoreCase = true)) {
                var v = kv.substring(9).trim()
                if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length - 1)
                return v.takeIf { it.isNotEmpty() && it.length <= 200 }
            }
        }
        return null
    }

    /** Parameter from a Content-Disposition header, preferring the RFC 5987 `name*=` form. */
    private fun dispositionParam(header: String, key: String): String? {
        val parts = header.split(';').map { it.trim() }
        parts.firstOrNull { it.startsWith("$key*=", ignoreCase = true) }?.let { p ->
            val v = p.substring(key.length + 2)
            val enc = v.substringAfterLast("''", v)
            runCatching { URLDecoder.decode(enc.replace("+", "%2B"), "UTF-8") }.getOrNull()?.let { return it }
        }
        parts.firstOrNull { it.startsWith("$key=", ignoreCase = true) }?.let { p ->
            var v = p.substring(key.length + 1).trim()
            if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length - 1)
            v = v.replace("\\\"", "\"")
            // Browsers put raw UTF-8 here; we decoded header bytes as ISO-8859-1, so re-decode.
            return String(v.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        }
        return null
    }

    /**
     * Streaming multipart/form-data reader. Owns a single 64 KiB window over the request body;
     * each part body is exposed as an InputStream that stops at the next boundary. Nothing
     * larger than the window is ever held in memory.
     */
    private class MultipartReader(private val src: InputStream, boundary: String) {
        private val delim = "\r\n--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
        private val first = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
        private val buf = ByteArray(64 * 1024 + 512)
        private var start = 0
        private var end = 0
        private var eof = false

        fun forEachPart(block: (headers: Map<String, String>, body: InputStream) -> Unit) {
            val preamble = PartStream(first)
            preamble.drain()
            if (!preamble.found) throw BadRequest("Multipart boundary not found")
            while (true) {
                // Right after a boundary: "--" marks the end, otherwise CRLF then part headers.
                val tail = readLine()
                if (tail.startsWith("--")) return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine()
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.US)] = line.substring(i + 1).trim()
                }
                val part = PartStream(delim)
                try {
                    block(headers, part)
                } finally {
                    part.drain()
                }
                if (!part.found) throw BadRequest("Unterminated multipart body")
            }
        }

        /** Pulls more bytes from the source into the window. False when nothing more could be read. */
        private fun fill(): Boolean {
            if (eof) return false
            if (start > 0) { System.arraycopy(buf, start, buf, 0, end - start); end -= start; start = 0 }
            if (end >= buf.size) return false
            val n = src.read(buf, end, buf.size - end)
            if (n < 0) { eof = true; return false }
            end += n
            return true
        }

        private fun readByte(): Int {
            if (start >= end && !fill()) return -1
            return buf[start++].toInt() and 0xff
        }

        private fun readLine(): String {
            val out = ByteArrayOutputStream(64)
            while (true) {
                val b = readByte()
                if (b < 0) throw BadRequest("Unexpected end of multipart body")
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
                if (out.size() > 16 * 1024) throw BadRequest("Multipart header too long")
            }
            return out.toString("ISO-8859-1")
        }

        private fun indexOf(needle: ByteArray): Int {
            val last = end - needle.size
            var i = start
            outer@ while (i <= last) {
                if (buf[i] != needle[0]) { i++; continue }
                for (j in 1 until needle.size) if (buf[i + j] != needle[j]) { i++; continue@outer }
                return i
            }
            return -1
        }

        /** Yields window bytes up to the next [needle]; consumes the needle itself and then reports EOF. */
        private inner class PartStream(private val needle: ByteArray) : InputStream() {
            var found = false
                private set

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, n: Int): Int {
                if (n == 0) return 0
                while (true) {
                    if (found) return -1
                    val idx = indexOf(needle)
                    if (idx >= 0) {
                        val avail = idx - start
                        if (avail == 0) { start += needle.size; found = true; return -1 }
                        val k = minOf(n, avail)
                        System.arraycopy(buf, start, b, off, k); start += k
                        return k
                    }
                    // No delimiter in the window: everything except a possible partial match at the tail is safe.
                    val safe = (end - start) - needle.size + 1
                    if (safe > 0) {
                        val k = minOf(n, safe)
                        System.arraycopy(buf, start, b, off, k); start += k
                        return k
                    }
                    if (!fill()) {
                        // Source exhausted without a delimiter: hand out what is left, then EOF (found stays false).
                        val avail = end - start
                        if (avail == 0) return -1
                        val k = minOf(n, avail)
                        System.arraycopy(buf, start, b, off, k); start += k
                        return k
                    }
                }
            }

            fun drain() {
                val skip = ByteArray(8 * 1024)
                while (read(skip, 0, skip.size) >= 0) { /* discard */ }
            }
        }
    }

    // ---------- response writing ----------

    private fun text(status: Int, body: String): Response =
        bytesResponse(status, "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun bytesResponse(status: Int, mime: String, bytes: ByteArray): Response =
        Response(status, mutableMapOf("Content-Type" to mime), bytes.size.toLong()) { it.write(bytes) }

    private fun writeResponse(out: OutputStream, r: Response, keepAlive: Boolean, headOnly: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(r.length).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("Server: Twinkle\r\nX-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\nContent-Security-Policy: frame-ancestors 'none'\r\nReferrer-Policy: no-referrer\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly && r.status != 204 && r.status != 304) r.write(out)
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 403 -> "Forbidden"; 429 -> "Too Many Requests"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"; else -> "OK"
    }

    // ---------- helpers ----------

    /** Bounded view over the connection stream so handlers cannot read past the request body. */
    private class LimitedInputStream(private val src: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(src.available().toLong(), remaining).toInt()

        fun drain() {
            val skip = ByteArray(8 * 1024)
            while (remaining > 0 && read(skip, 0, skip.size) >= 0) { /* discard */ }
        }
    }

    companion object {
        const val PORT = 8080
        const val RELATIVE_PATH = "Download/Twinkle"
        private const val TAG = "TwinkleHttp"
        private const val MAX_CONNECTIONS = 32
        private const val MAX_UPLOAD_BYTES = 1L shl 40
        private const val MAX_CHUNK_BYTES = 16L * 1024 * 1024
        private const val MAX_OPEN_UPLOADS = 64
        private const val STALE_UPLOAD_MS = 60L * 60 * 1000
        /** Interface name prefixes PC mode may be reached on: Wi-Fi Direct group, USB tethering. */
        /** Interface name prefix of the Wi-Fi Direct group the phone hosts. */
        val WIFI_DIRECT_IFACES = listOf("p2p")
        private val ALLOWED_IFACES = WIFI_DIRECT_IFACES + UsbTether.PREFIXES
        /** True for the network interfaces PC mode answers on (the Wi-Fi Direct group and USB tethering) and nothing else. */
        internal fun interfaceAllowed(name: String): Boolean = ALLOWED_IFACES.any { name.startsWith(it) }
        /** The phone's own Wi-Fi (as a client or as its hotspot) and Ethernet: shared networks, PIN only. */
        private val LAN_IFACES = listOf("wlan", "swlan", "eth", "ap")
        /**
         * [trusted] = the links the running mode owns. A PC-mode link outside it (the USB tether during a Wi-Fi
         * session, say) is refused, so each mode answers on its own link only.
         */
        internal fun linkOfInterface(name: String, trusted: List<String> = ALLOWED_IFACES): Link = when {
            trusted.any { name.startsWith(it) } -> Link.TRUSTED
            interfaceAllowed(name) -> Link.REFUSED
            LAN_IFACES.any { name.startsWith(it) } -> Link.LAN
            else -> Link.REFUSED
        }
        private const val COOKIE = "twinkle_s"
        private const val MAX_SESSIONS = 32
        private const val MAX_FAILS_PER_IP = 5
        private const val FAIL_WINDOW_MS = 5L * 60 * 1000
        private const val MAX_FAILS_TOTAL = 20

        /** A fresh six-digit PIN. */
        // Locale.ROOT: under an Arabic locale "%06d" yields Arabic-Indic digits, which no PC keyboard types.
        fun newPin(): String = String.format(Locale.ROOT, "%06d", java.security.SecureRandom().nextInt(1_000_000))

        /** The phone's IPv4 address on its own Wi-Fi or Ethernet network, if it has one. Ethernet first. */
        fun lanAddress(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && linkOfInterface(it.name) == Link.LAN }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress } }
                .firstOrNull()?.hostAddress
        }.getOrNull()
        private val ASSET_NAME =Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}")
        private val IP_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$|^\[[0-9A-Fa-f:.%]+]$""")

        /** Host part of a Host/Origin value without the port, brackets kept for IPv6; null when absent. */
        fun hostOf(value: String?): String? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (v.startsWith("[")) v.substringBefore(']') + "]" else v.substringBefore(':')
        }

        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return when (ext) {
                "html", "htm" -> "text/html; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "js" -> "application/javascript; charset=utf-8"
                "json" -> "application/json; charset=utf-8"
                "txt" -> "text/plain; charset=utf-8"
                "svg" -> "image/svg+xml"
                "apk" -> "application/vnd.android.package-archive"
                "heic" -> "image/heic"
                "mkv" -> "video/x-matroska"
                "" -> "application/octet-stream"
                else -> MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }

        /** Display-safe name: no path pieces, no control characters, bounded length. */
        fun displayName(name: String): String = NameSanitizer.fileName(name)

        /** Name safe to hand to MediaStore: the same rules the phone-to-phone receiver applies. */
        fun sanitizeName(name: String): String = NameSanitizer.fileName(name)

        private fun asciiName(name: String): String =
            displayName(name).map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")

        private fun percentEncode(s: String): String =
            URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    }
}
