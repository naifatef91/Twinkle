package app.twinkle.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Where PC-mode uploads land while they are being received in pieces. The browser sends a file as a series of
 * chunks at explicit offsets, so a dropped connection costs at most one chunk: it asks how far the phone got and
 * carries on from there. Kept behind an interface so the whole protocol can be tested against a temp directory.
 */
interface UploadStore {
    /** Starts an empty, hidden entry for [name]; returns an opaque token for it. */
    fun create(name: String, mime: String): String

    /**
     * Writes exactly [length] bytes read from [data] at [offset] of the entry. Returns how many bytes arrived,
     * which is less than [length] only when the stream ended early (the connection dropped).
     */
    fun writeAt(token: String, offset: Long, data: InputStream, length: Long): Long

    /** Makes the entry visible and returns the name it finally got (the system may rename on a clash). */
    fun finish(token: String, fallbackName: String): String

    /** Removes an unfinished entry. */
    fun discard(token: String)
}

/** MediaStore Downloads/Twinkle: pending (invisible to other apps) until [finish]. */
class MediaStoreUploads(private val context: Context) : UploadStore {
    override fun create(name: String, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, HttpServer.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("could not create $name")
        return uri.toString()
    }

    override fun writeAt(token: String, offset: Long, data: InputStream, length: Long): Long {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(token), "rw") ?: throw IOException("cannot open entry")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                val ch = out.channel
                ch.position(offset)
                val buf = ByteArray(64 * 1024)
                var left = length
                var done = 0L
                while (left > 0) {
                    val n = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n; done += n
                }
                out.flush()
                return done
            }
        }
    }

    override fun finish(token: String, fallbackName: String): String {
        val resolver = context.contentResolver
        val uri = Uri.parse(token)
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: fallbackName
    }

    override fun discard(token: String) {
        runCatching { context.contentResolver.delete(Uri.parse(token), null, null) }
    }
}
