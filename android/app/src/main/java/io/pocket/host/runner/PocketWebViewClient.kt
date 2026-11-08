package io.pocket.host.runner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.pocket.host.core.InstalledApp
import io.pocket.host.core.TransferStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 本地虚拟源：https://&lt;appId&gt;.pocket.local/{path}
 * 命中文件则从 files/apps/&lt;id&gt;/www 返回；否则一律拒绝。
 */
class PocketWebViewClient(
    private val context: Context,
    private val app: InstalledApp,
    private val wwwDir: File,
    private val sdkProvider: () -> String,
    private val onPageLoaded: () -> Unit = {}
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != app.host) {
            // 外部 http(s) 子资源（CDN 脚本、字体、图片）正常走网络；
            // 非网络协议一律拒绝，避免 file:// 逃逸。
            return if (url.scheme == "https" || url.scheme == "http") {
                null
            } else {
                emptyResponse(403, "Forbidden")
            }
        }

        val path = Uri.decode(url.path ?: "/")

        if (path == SDK_PATH) {
            return WebResourceResponse(
                "application/javascript", "UTF-8", 200, "OK",
                mapOf(
                    "Cache-Control" to "no-store",
                    "Access-Control-Allow-Origin" to app.origin
                ),
                ByteArrayInputStream(sdkProvider().toByteArray())
            )
        }

        // 口袋文件：页面可直接 <img>/<video>/fetch 这些地址，读取的是互传目录里的文件
        if (path.startsWith(FILES_PATH)) {
            val id = path.removePrefix(FILES_PATH)
            val entry = TransferStore.get(context, id)
            val file = TransferStore.fileOf(context, id)
            if (entry == null || file == null) return emptyResponse(404, "Not Found")
            val headers = mutableMapOf(
                "Cache-Control" to "no-store",
                "Accept-Ranges" to "bytes"
            )
            val range = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
            // 互传时对端可能只给 application/octet-stream，按扩展名纠正，否则图片/视频无法预览
            val stored = entry.mime.trim().lowercase()
            val mime = if (stored.isBlank() || stored == "application/octet-stream") {
                mimeOf(entry.name)
            } else {
                entry.mime
            }
            return respondFile(file, mime, headers, range)
        }

        val relative = when {
            path.isEmpty() || path == "/" -> app.entry
            path.startsWith("/") -> path.substring(1)
            else -> path
        }

        if (relative.contains("..")) return emptyResponse(403, "Forbidden")

        val file = File(wwwDir, relative)
        if (!file.isFile) return emptyResponse(404, "Not Found")

        val mime = mimeOf(file.name)
        val isText = mime.startsWith("text/") || mime.contains("json") ||
            mime.contains("javascript") || mime.contains("xml") || mime.contains("svg")

        return if (mime == "text/html") {
            val html = injectIntoHtml(file.readText(), app)
            WebResourceResponse(
                mime, "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(html.toByteArray())
            )
        } else {
            val headers = mutableMapOf(
                "Cache-Control" to if (isText) "no-cache" else "max-age=86400",
                "Accept-Ranges" to "bytes"
            )
            val range = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
            respondFile(file, mime, headers, range)
        }
    }

    private fun respondFile(
        file: File,
        mime: String,
        headers: MutableMap<String, String>,
        range: String?
    ): WebResourceResponse {
        val total = file.length()
        val parsed = parseRange(range, total)
        if (parsed == null) {
            if (range != null) {
                headers["Content-Range"] = "bytes */$total"
                return WebResourceResponse(
                    mime, null, 416, "Range Not Satisfiable", headers,
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            headers["Content-Length"] = total.toString()
            return WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(file))
        }

        val (start, end) = parsed
        val length = end - start + 1
        headers["Content-Range"] = "bytes $start-$end/$total"
        headers["Content-Length"] = length.toString()
        val stream = FileInputStream(file)
        skipFully(stream, start)
        return WebResourceResponse(
            mime, null, 206, "Partial Content", headers,
            LimitedInputStream(stream, length)
        )
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageLoaded()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme == "https" && url.host == app.host) return false
        if (url.scheme == "http" || url.scheme == "https") {
            if ("external" !in app.permissions) return true
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return true
        }
        // file:// content:// intent:// javascript: 等一律拦截
        return true
    }

    private fun emptyResponse(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun parseRange(range: String?, total: Long): Pair<Long, Long>? {
        if (range == null || total <= 0) return null
        if (!range.startsWith("bytes=")) return null
        val spec = range.removePrefix("bytes=").split(",").firstOrNull() ?: return null
        val parts = spec.split("-")
        if (parts.size != 2) return null
        val startText = parts[0].trim()
        val endText = parts[1].trim()
        val start: Long
        val end: Long
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (total - suffix).coerceAtLeast(0)
            end = total - 1
        } else {
            start = startText.toLongOrNull() ?: return null
            end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return null)
        }
        if (start > end || start >= total) return null
        return start to end.coerceAtMost(total - 1)
    }

    /** 保证 SDK 在页面任何脚本之前执行，并补齐移动端视口设置。 */
    private fun injectIntoHtml(html: String, app: InstalledApp): String {
        val lower = html.lowercase()
        val hasViewport = lower.contains("name=\"viewport\"") || lower.contains("name='viewport'")
        val viewportTag = if (hasViewport) "" else
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover\">"
        val bootstrap = viewportTag + "<script src=\"$SDK_PATH\"></script>"

        val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (headMatch != null) {
            val at = headMatch.range.last + 1
            return html.substring(0, at) + bootstrap + html.substring(at)
        }
        val htmlMatch = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (htmlMatch != null) {
            val at = htmlMatch.range.last + 1
            return html.substring(0, at) + "<head>$bootstrap</head>" + html.substring(at)
        }
        return bootstrap + html
    }

    companion object {
        const val SDK_PATH = "/__pocket/sdk.js"
        const val FILES_PATH = "/__pocket/files/"

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "ico" -> "image/x-icon"
                "bmp" -> "image/bmp"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "eot" -> "application/vnd.ms-fontobject"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "xml" -> "application/xml"
                "wasm" -> "application/wasm"
                "map" -> "application/json"
                else -> "application/octet-stream"
            }
        }

        fun isHtml(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext == "html" || ext == "htm"
        }
    }
}

/** 只读取固定长度的流，配合 Range 响应。 */
private class LimitedInputStream(
    private val source: InputStream,
    private var remaining: Long
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val read = source.read(b, off, toRead)
        if (read > 0) remaining -= read
        return read
    }

    override fun close() {
        source.close()
    }
}
