package io.pocket.host.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把本机文件推给另一台设备（手机或电脑）。
 * 流程：POST /api/transfer/offer 拿 token → 逐个 PUT /api/transfer/upload/<token>/<i>。
 */
object TransferClient {

    data class Source(
        val name: String,
        val mime: String,
        val size: Long,
        val open: () -> InputStream?
    )

    class TransferException(message: String) : Exception(message)

    fun send(
        context: Context,
        peer: Peer,
        sources: List<Source>,
        onProgress: (name: String, sent: Long, total: Long, speed: Long) -> Unit = { _, _, _, _ -> }
    ): List<String> {
        if (sources.isEmpty()) throw TransferException("没有要发送的文件")
        val base = "http://${peer.host}:${peer.port}"

        val files = JSONArray()
        sources.forEach { source ->
            files.put(
                JSONObject()
                    .put("name", source.name)
                    .put("size", source.size)
                    .put("mime", source.mime)
            )
        }
        val offer = JSONObject()
            .put(
                "from",
                JSONObject()
                    .put("id", TransferDiscovery.deviceId)
                    .put("name", TransferDiscovery.deviceName)
                    .put("platform", "android")
            )
            .put("files", files)

        val offerReply = postJson("$base/api/transfer/offer", offer.toString())
        if (!offerReply.optBoolean("accepted", false)) {
            throw TransferException(offerReply.optString("error", "对方拒绝接收"))
        }
        val token = offerReply.optString("token")
        if (token.isBlank()) throw TransferException("对方没有返回传输凭证")

        val sent = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            uploadOne("$base/api/transfer/upload/$token/$index", source, onProgress)
            sent.add(source.name)
        }
        return sent
    }

    private fun uploadOne(
        url: String,
        source: Source,
        onProgress: (String, Long, Long, Long) -> Unit
    ) {
        val stream = source.open() ?: throw TransferException("读取不到「${source.name}」")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 6_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Type", source.mime.ifBlank { "application/octet-stream" })
            if (source.size > 0) {
                setFixedLengthStreamingMode(source.size)
            } else {
                setChunkedStreamingMode(256 * 1024)
            }
        }
        try {
            stream.use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    var lastBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                        val now = System.currentTimeMillis()
                        if (now - lastTime > 250) {
                            val speed = (total - lastBytes) * 1000 / (now - lastTime).coerceAtLeast(1)
                            onProgress(
                                source.name, total,
                                if (source.size > 0) source.size else total,
                                speed
                            )
                            lastBytes = total
                            lastTime = now
                        }
                    }
                    output.flush()
                    onProgress(
                        source.name, total,
                        if (source.size > 0) source.size else total,
                        0
                    )
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw TransferException("发送「${source.name}」失败：HTTP $code ${body.take(120)}")
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Throwable) {
            throw TransferException("发送「${source.name}」失败：${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optString("error")
                }.getOrNull()
                throw TransferException("对方返回 HTTP $code：${message?.take(120) ?: text.take(120)}")
            }
            runCatching { JSONObject(text) }.getOrElse { throw TransferException("对方返回格式异常") }
        } catch (e: TransferException) {
            throw e
        } catch (e: Throwable) {
            throw TransferException("连接失败：${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /** 从「口袋文件」里取文件当发送源 */
    fun sourceOf(context: Context, entry: TransferEntry): Source {
        val file = File(TransferStore.root(context), entry.relPath)
        return Source(
            name = entry.name,
            mime = entry.mime,
            size = entry.size
        ) { runCatching { file.inputStream() }.getOrNull() }
    }
}
