package io.pocket.host.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 手机端互传 HTTP 服务：接收其它设备发来的文件，并提供「口袋文件」的下载/预览。
 * 端口默认 8572，被占用时顺延。
 */
object TransferServer {

    private const val DEFAULT_PORT = 8572
    private const val PORT_TRIES = 8
    private const val MAX_JSON_BODY = 1024L * 1024

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var port: Int = 0
        private set

    private var contextRef: Context? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val pending = ConcurrentHashMap<String, Pending>()

    private data class OfferFile(val name: String, val size: Long, val mime: String)

    private data class Pending(
        val files: List<OfferFile>,
        val fromName: String,
        val fromId: String,
        val createdAt: Long,
        val done: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())
    )

    fun start(context: Context): Int {
        if (running) return port
        contextRef = context.applicationContext
        var candidate = DEFAULT_PORT
        while (candidate < DEFAULT_PORT + PORT_TRIES) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate))
                serverSocket = socket
                port = candidate
                running = true
                thread(start = true, isDaemon = true, name = "pocket-transfer-http") {
                    acceptLoop(socket)
                }
                return candidate
            } catch (e: Throwable) {
                candidate++
            }
        }
        port = 0
        return 0
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Throwable) {
                break
            }
            runCatching { pool.execute { handle(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    // ---------------------------------------------------------------- 请求处理

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
            route(method, path, headers, input, output)
            output.flush()
        } catch (e: Throwable) {
            runCatching { socket.getOutputStream().write(errorBytes(500, e.message ?: "服务器错误")) }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val context = contextRef ?: return sendJson(output, 500, JSONObject().put("error", "服务未启动"))

        if (method == "OPTIONS") return sendEmpty(output, 204)

        when {
            // ---------------------------------------------------------- 剪贴板
            path == "/api/clipboard" && method == "GET" -> {
                val array = JSONArray()
                ClipboardStore.list(context).forEach { array.put(it.toJson()) }
                sendJson(output, 200, JSONObject().put("ok", true).put("items", array))
            }

            path == "/api/clipboard" && method == "POST" -> {
                val body = readBody(input, headers, MAX_JSON_BODY)
                val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                if (json == null) {
                    return sendJson(output, 400, JSONObject().put("error", "请求格式错误"))
                }
                val text = json.optString("text")
                if (text.isBlank()) {
                    return sendJson(output, 400, JSONObject().put("error", "text 不能为空"))
                }
                val entry = ClipboardStore.add(
                    context = context,
                    text = text,
                    source = json.optString("source", "pc"),
                    time = json.optLong("time", 0L)
                ) ?: return sendJson(output, 400, JSONObject().put("error", "保存失败"))
                sendJson(output, 200, JSONObject().put("ok", true).put("item", entry.toJson()))
                TransferEvents.emit("clipboard.changed", entry.toJson())
            }

            path == "/api/clipboard/clear" && method == "POST" -> {
                ClipboardStore.clear(context)
                sendJson(output, 200, JSONObject().put("ok", true))
                TransferEvents.emit("clipboard.changed", JSONObject().put("cleared", true))
            }

            path.startsWith("/api/clipboard/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/clipboard/")
                val ok = id.isNotBlank() && ClipboardStore.remove(context, id)
                sendJson(output, if (ok) 200 else 404, JSONObject().put("ok", ok))
                if (ok) {
                    TransferEvents.emit("clipboard.changed", JSONObject().put("removed", id))
                }
            }

            (path == "/api/transfer/info" || path == "/api/transfer/ping" || path == "/") &&
                method == "GET" -> {
                val json = TransferDiscovery.infoJson()
                    .put("ok", true)
                    .put("ip", TransferDiscovery.localIp() ?: "")
                    .put("time", System.currentTimeMillis())
                sendJson(output, 200, json)
            }

            path == "/api/transfer/offer" && method == "POST" -> {
                val body = readBody(input, headers, MAX_JSON_BODY)
                val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                if (json == null) return sendJson(output, 400, JSONObject().put("error", "请求格式错误"))

                val array = json.optJSONArray("files")
                val files = mutableListOf<OfferFile>()
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val name = TransferStore.sanitize(item.optString("name", "file"))
                        files.add(
                            OfferFile(
                                name = name,
                                size = item.optLong("size", 0L),
                                mime = item.optString("mime", "application/octet-stream")
                            )
                        )
                    }
                }
                if (files.isEmpty()) {
                    return sendJson(output, 400, JSONObject().put("error", "files 不能为空"))
                }

                expirePending()
                val token = UUID.randomUUID().toString().replace("-", "")
                val from = json.optJSONObject("from")
                pending[token] = Pending(
                    files = files,
                    fromName = from?.optString("name").orEmpty().ifBlank { "未知设备" },
                    fromId = from?.optString("id").orEmpty(),
                    createdAt = System.currentTimeMillis()
                )
                sendJson(
                    output, 200,
                    JSONObject().put("ok", true).put("accepted", true).put("token", token)
                )
                TransferEvents.emit(
                    "transfer.offer",
                    JSONObject()
                        .put("from", from?.optString("name").orEmpty())
                        .put("count", files.size)
                )
            }

            path.startsWith("/api/transfer/upload/") && method == "PUT" -> {
                val rest = path.removePrefix("/api/transfer/upload/")
                val bits = rest.split('/')
                val token = bits.getOrNull(0).orEmpty()
                val index = bits.getOrNull(1)?.toIntOrNull() ?: -1
                val offer = pending[token]
                if (offer == null) {
                    return sendJson(output, 404, JSONObject().put("error", "传输已过期，请重发"))
                }
                if (index !in offer.files.indices) {
                    return sendJson(output, 400, JSONObject().put("error", "文件序号不对"))
                }

                val info = offer.files[index]
                val target = TransferStore.newIncomingFile(context, info.name)
                var lastBytes = 0L
                var lastAt = System.currentTimeMillis()
                var lastEmit = 0L
                val written = try {
                    copyBody(input, headers, target) { sent ->
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250) {
                            val speed = if (now > lastAt) {
                                (sent - lastBytes) * 1000 / (now - lastAt).coerceAtLeast(1)
                            } else 0
                            lastEmit = now
                            lastAt = now
                            lastBytes = sent
                            emitProgress(info.name, sent, info.size, speed, offer.fromName)
                        }
                    }
                } catch (e: Throwable) {
                    runCatching { target.delete() }
                    return sendJson(output, 500, JSONObject().put("error", "写入失败：${e.message}"))
                }
                if (written <= 0) {
                    runCatching { target.delete() }
                    return sendJson(output, 400, JSONObject().put("error", "空文件"))
                }
                emitProgress(info.name, written, info.size, 0, offer.fromName)

                val entry = TransferStore.add(
                    context = context,
                    file = target,
                    name = target.name,
                    mime = info.mime.ifBlank { "application/octet-stream" },
                    direction = "in",
                    peer = offer.fromName
                )
                offer.done.add(index)
                if (offer.done.size >= offer.files.size) pending.remove(token)

                sendJson(output, 200, entry.toJson().put("ok", true))
                TransferEvents.emit("transfer.received", entry.toJson())
            }

            path == "/api/transfer/files" && method == "GET" -> {
                val array = JSONArray()
                TransferStore.list(context).forEach { array.put(it.toJson()) }
                sendJson(output, 200, JSONObject().put("ok", true).put("files", array))
            }

            path.startsWith("/api/transfer/file/") && (method == "GET" || method == "HEAD") -> {
                val id = path.removePrefix("/api/transfer/file/")
                val entry = TransferStore.get(context, id)
                val file = entry?.let { TransferStore.fileOf(context, it.id) }
                if (entry == null || file == null) {
                    return sendJson(output, 404, JSONObject().put("error", "文件不存在"))
                }
                val mime = entry.mime.ifBlank { "application/octet-stream" }
                sendFile(output, file, mime, headers["range"], method == "HEAD")
            }

            path.startsWith("/api/transfer/file/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/transfer/file/")
                val ok = TransferStore.delete(context, id)
                sendJson(output, if (ok) 200 else 404, JSONObject().put("ok", ok))
            }

            path == "/api/transfer/delete" && method == "POST" -> {
                val body = readBody(input, headers, MAX_JSON_BODY)
                val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                val id = json?.optString("id").orEmpty()
                val ok = id.isNotBlank() && TransferStore.delete(context, id)
                sendJson(output, if (ok) 200 else 404, JSONObject().put("ok", ok))
            }

            else -> sendJson(output, 404, JSONObject().put("error", "not found"))
        }
    }

    // ---------------------------------------------------------------- 文件

    private fun sendFile(
        output: OutputStream,
        file: File,
        mime: String,
        rangeHeader: String?,
        headOnly: Boolean
    ) {
        val total = file.length()
        val range = parseRange(rangeHeader, total)
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1)
        val length = if (total == 0L) 0L else end - start + 1

        val head = StringBuilder()
        head.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(mime).append("\r\n")
        head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        head.append("Cache-Control: no-store\r\n")
        if (range != null) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                .append('/').append(total).append("\r\n")
        }
        cors(head)
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))

        if (headOnly || total == 0L) return
        FileInputStream(file).use { stream ->
            skipFully(stream, start)
            copyLimited(stream, output, length)
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0 || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").split(',').firstOrNull()?.trim() ?: return null
        val parts = spec.split('-')
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

    private fun copyBody(
        input: InputStream,
        headers: Map<String, String>,
        target: File,
        onProgress: (Long) -> Unit = {}
    ): Long {
        target.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(target), 256 * 1024).use { output ->
            val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            return if (chunked) {
                copyChunked(input, output, onProgress)
            } else {
                val length = headers["content-length"]?.toLongOrNull() ?: -1L
                if (length >= 0) {
                    copyLimited(input, output, length, onProgress)
                } else {
                    copyChunked(input, output, onProgress)
                }
            }
        }
    }

    private fun copyChunked(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {}
    ): Long {
        var total = 0L
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: break
            if (size == 0L) {
                readLine(input)
                break
            }
            total += copyLimited(input, output, size) { onProgress(total + it) }
            readLine(input)
        }
        return total
    }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        length: Long,
        onProgress: (Long) -> Unit = {}
    ): Long {
        var remaining = length
        var copied = 0L
        val buffer = ByteArray(256 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
            copied += read
            onProgress(copied)
        }
        return copied
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun readBody(input: InputStream, headers: Map<String, String>, limit: Long): ByteArray {
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val out = ByteArrayOutputStream()
        if (chunked) {
            while (true) {
                val line = readLine(input) ?: break
                val size = line.substringBefore(';').trim().toLongOrNull(16) ?: break
                if (size == 0L) {
                    readLine(input)
                    break
                }
                val buffer = ByteArray(size.toInt())
                var read = 0
                while (read < size) {
                    val n = input.read(buffer, read, (size - read).toInt())
                    if (n <= 0) break
                    read += n
                }
                out.write(buffer, 0, read)
                readLine(input)
                if (out.size() > limit) break
            }
        } else {
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            val cap = minOf(length, limit)
            copyLimited(input, out, cap)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) {
                val text = buffer.toString("UTF-8")
                return if (text.endsWith("\r")) text.dropLast(1) else text
            }
            buffer.write(b)
        }
    }

    private fun cors(builder: StringBuilder) {
        builder.append("Access-Control-Allow-Origin: *\r\n")
        builder.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
        builder.append("Access-Control-Allow-Headers: Content-Type, Range\r\n")
        builder.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
    }

    private fun sendJson(output: OutputStream, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        head.append("Content-Type: application/json; charset=utf-8\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        cors(head)
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        output.write(bytes)
    }

    private fun sendEmpty(output: OutputStream, code: Int) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        head.append("Content-Length: 0\r\n")
        cors(head)
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
    }

    private fun errorBytes(code: Int, message: String): ByteArray {
        val body = JSONObject().put("error", message).toString()
        return ("HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n$body").toByteArray(Charsets.UTF_8)
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun expirePending() {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.createdAt > 10 * 60 * 1000 }
    }

    private fun emitProgress(name: String, sent: Long, total: Long, speed: Long, peer: String) {
        val percent = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
        TransferEvents.emit(
            "transfer.progress",
            JSONObject()
                .put("name", name)
                .put("sent", sent)
                .put("total", total)
                .put("percent", percent)
                .put("speed", speed)
                .put("direction", "in")
                .put("peer", peer)
        )
    }
}
