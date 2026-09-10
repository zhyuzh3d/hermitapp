package io.github.zhyuzh3d.hermit.runtime

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection

class LocalContentGateway private constructor(
    private val context: Context,
    private val host: String,
    private val root: File?,
    private val assetPrefix: String?,
    private val historyFallback: Boolean,
) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!uri.scheme.equals("https", true) || !uri.host.equals(host, true)) return null
        if (request.method != "GET" && request.method != "HEAD") return response(405, "Method Not Allowed", "text/plain", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val raw = Uri.decode(uri.encodedPath ?: "/").removePrefix("/")
        if (raw.startsWith("__hermit/")) return response(404, "Not Found", "text/plain", emptyMap(), empty())
        val path = if (raw.isBlank()) "index.html" else raw
        val safe = !path.contains('\u0000') && path.split('/').none { it == ".." || it == "." || it.isBlank() }
        if (!safe) {
            return response(400, "Bad Request", "text/plain", emptyMap(), empty())
        }
        return if (root != null) fileResponse(request, path) else assetResponse(path)
    }

    private fun fileResponse(request: WebResourceRequest, path: String): WebResourceResponse {
        val canonicalRoot = root!!.canonicalFile
        var file = File(canonicalRoot, path).canonicalFile
        if (!file.path.startsWith(canonicalRoot.path + File.separator) || !file.isFile) {
            if (historyFallback && request.isForMainFrame && request.requestHeaders["Accept"]?.contains("text/html") == true) {
                file = File(canonicalRoot, "index.html")
            }
        }
        if (!file.isFile || !file.path.startsWith(canonicalRoot.path + File.separator)) {
            return response(404, "Not Found", mime(path), headers(), empty())
        }
        val range = request.requestHeaders["Range"]?.let { parseRange(it, file.length()) }
        if (request.requestHeaders.containsKey("Range") && range == null) {
            return response(416, "Range Not Satisfiable", mime(path), headers() + ("Content-Range" to "bytes */${file.length()}"), empty())
        }
        if (range != null) {
            val (start, end) = range
            val length = end - start + 1
            val stream = BoundedFileInputStream(file, start, length)
            return response(206, "Partial Content", mime(path), headers() + mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes $start-$end/${file.length()}",
                "Content-Length" to length.toString(),
            ), stream)
        }
        return response(200, "OK", mime(path), headers() + mapOf(
            "Content-Length" to file.length().toString(),
            "Accept-Ranges" to "bytes",
        ), FileInputStream(file))
    }

    private fun assetResponse(path: String): WebResourceResponse {
        val fullPath = "$assetPrefix/$path"
        return try {
            val stream = context.assets.open(fullPath)
            response(200, "OK", mime(path), headers(), stream)
        } catch (_: Throwable) {
            response(404, "Not Found", "text/plain", headers(), empty())
        }
    }

    private fun headers(): Map<String, String> {
        val policy = if (assetPrefix != null) {
            "default-src 'self'; connect-src 'none'; img-src 'self' data:; " +
                "style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
        } else {
            "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
        }
        return mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Service-Worker-Allowed" to "https://sw-disabled.hermit.invalid/",
            "Content-Security-Policy" to policy,
        )
    }

    private fun parseRange(value: String, length: Long): Pair<Long, Long>? {
        if (!value.startsWith("bytes=") || value.contains(',')) return null
        val parts = value.removePrefix("bytes=").split('-', limit = 2)
        if (parts.size != 2) return null
        val start: Long
        val end: Long
        if (parts[0].isBlank()) {
            val suffix = parts[1].toLongOrNull()?.coerceAtMost(length) ?: return null
            if (suffix <= 0) return null
            start = length - suffix
            end = length - 1
        } else {
            start = parts[0].toLongOrNull() ?: return null
            end = if (parts[1].isBlank()) length - 1 else parts[1].toLongOrNull() ?: return null
        }
        if (start < 0 || end < start || start >= length || end >= length) return null
        return start to end
    }

    private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
    }

    private fun response(code: Int, reason: String, mime: String, headers: Map<String, String>, stream: InputStream) =
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null, code, reason, headers, stream)

    private fun empty() = ByteArrayInputStream(ByteArray(0))

    companion object {
        fun forStore(context: Context) = LocalContentGateway(context, "store.hermit.invalid", null, "store", false)
        fun forRelease(context: Context, host: String, root: File, historyFallback: Boolean) = LocalContentGateway(context, host, root, null, historyFallback)
    }
}

private class BoundedFileInputStream(file: File, offset: Long, private var remaining: Long) : InputStream() {
    private val input = FileInputStream(file).also { channel -> channel.channel.position(offset) }
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = input.read()
        if (value >= 0) remaining--
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }
    override fun close() = input.close()
}
