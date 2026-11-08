package io.pocket.host.core

import android.content.Context
import io.pocket.host.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 生图模型配置与调用（OpenAI 兼容中转站）。
 *
 * - 没参考图：POST {base}/images/generations（JSON）
 * - 有参考图：POST {base}/images/edits（multipart，字段 image / image[]）
 *
 * Key 默认值来自构建时注入的 BuildConfig.IMAGE_API_KEY（来源是本地
 * android/image-secrets.properties，已被 .gitignore 排除，不会进仓库）；
 * 用户也可以在「设置 → 生图模型」里改成自己的。
 */
object ImageConfig {

    private const val PREFS = "pocket"
    private const val K_BASE = "image_base"
    private const val K_KEY = "image_key"
    private const val K_MODEL = "image_model"

    const val DEFAULT_BASE = "https://wawapii.com/v1"
    const val DEFAULT_MODEL = "gpt-image-2.5-flare"

    val SIZES = listOf("auto", "1024x1024", "1536x1024", "1024x1536")
    val QUALITIES = listOf("auto", "low", "medium", "high")
    val FORMATS = listOf("png", "jpeg", "webp")

    data class Settings(val baseUrl: String, val key: String, val model: String) {
        fun ready(): Boolean = baseUrl.isNotBlank() && key.isNotBlank() && model.isNotBlank()
        fun summary(): String = if (!ready()) "未配置" else "$model · ${hostOf(baseUrl)}"
    }

    data class RefImage(val bytes: ByteArray, val mime: String = "image/png")

    class ImageException(message: String) : Exception(message)

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            baseUrl = p.getString(K_BASE, DEFAULT_BASE).orEmpty().ifBlank { DEFAULT_BASE },
            key = p.getString(K_KEY, null) ?: BuildConfig.IMAGE_API_KEY,
            model = p.getString(K_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_BASE, s.baseUrl.trim().trimEnd('/'))
            .putString(K_KEY, s.key.trim())
            .putString(K_MODEL, s.model.trim())
            .apply()
    }

    fun ready(context: Context): Boolean = load(context).ready()

    /**
     * 生成图片，**阻塞**调用，需放在子线程。
     * @return 落在 cacheDir/imagegen/ 下的图片文件，顺序与接口返回一致。
     */
    fun generate(
        context: Context,
        prompt: String,
        size: String = "auto",
        quality: String = "auto",
        count: Int = 1,
        outputFormat: String = "png",
        refs: List<RefImage> = emptyList()
    ): List<File> {
        if (prompt.isBlank()) throw ImageException("提示词不能为空")
        val cfg = load(context)
        if (!cfg.ready()) throw ImageException("还没有配置生图模型，去 设置 → 生图模型 填一下")

        val n = count.coerceIn(1, 4)
        val realSize = if (size in SIZES) size else "auto"
        val realQuality = if (quality in QUALITIES) quality else "auto"
        val realFormat = if (outputFormat in FORMATS) outputFormat else "png"

        val bytesList = if (refs.isEmpty()) {
            val payload = JSONObject()
                .put("model", cfg.model)
                .put("prompt", prompt)
                .put("n", n)
                .put("size", realSize)
                .put("quality", realQuality)
                .put("output_format", realFormat)
            requestJson(cfg, "/images/generations", payload.toString())
        } else {
            val fields = mutableListOf<Pair<String, String>>()
            fields.add("model" to cfg.model)
            fields.add("prompt" to prompt)
            fields.add("n" to n.toString())
            fields.add("size" to realSize)
            fields.add("quality" to realQuality)
            fields.add("output_format" to realFormat)
            // 该中转站单图/多图都接受 image[]（已实测），统一用它最稳
            val files = refs.mapIndexed { index, ref ->
                Triple("image[]", "ref${index + 1}${extOf(ref.mime)}", ref.bytes)
            }
            requestMultipart(cfg, "/images/edits", fields, files)
        }

        if (bytesList.isEmpty()) throw ImageException("接口没有返回图片")
        val dir = File(context.cacheDir, "imagegen").apply { mkdirs() }
        val expire = System.currentTimeMillis() - 24L * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < expire) it.delete() }

        val ext = when (realFormat) {
            "jpeg" -> "jpg"
            "webp" -> "webp"
            else -> "png"
        }
        // 有些中转站不管 output_format，一律回 png，按文件头纠正扩展名
        return bytesList.mapIndexed { index, bytes ->
            val realExt = sniffExt(bytes) ?: ext
            val file = File(dir, "img_${System.currentTimeMillis()}_${index}_${UUID.randomUUID().toString().take(6)}.$realExt")
            file.writeBytes(bytes)
            file
        }
    }

    // ---------------------------------------------------------------- 请求

    private fun requestJson(cfg: Settings, path: String, body: String): List<ByteArray> {
        val connection = open(cfg, path).apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            parseResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestMultipart(
        cfg: Settings,
        path: String,
        fields: List<Pair<String, String>>,
        files: List<Triple<String, String, ByteArray>>
    ): List<ByteArray> {
        val boundary = "----Pocket${System.currentTimeMillis()}"
        val body = buildMultipart(boundary, fields, files)
        val connection = open(cfg, path).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(body) }
            parseResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(cfg: Settings, path: String): HttpURLConnection {
        return try {
            (URL(cfg.baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 300_000
                setRequestProperty("Authorization", "Bearer ${cfg.key}")
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Throwable) {
            throw ImageException("连接失败：${e.message}")
        }
    }

    private fun parseResponse(connection: HttpURLConnection): List<ByteArray> {
        val code = try {
            connection.responseCode
        } catch (e: Throwable) {
            throw ImageException("连接中断：${e.message}")
        }
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            throw ImageException("生图接口返回 $code：${errorMessage(text)}")
        }

        val root = runCatching { JSONObject(text) }.getOrElse {
            throw ImageException("返回格式无法解析：${text.take(120)}")
        }
        val data = root.optJSONArray("data")
        if (data == null || data.length() == 0) {
            val err = errorMessage(text)
            throw ImageException(if (err.isNotBlank()) err else "接口没有返回图片")
        }

        val out = mutableListOf<ByteArray>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val b64 = item.optString("b64_json")
            if (b64.isNotBlank()) {
                out.add(
                    runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
                        .getOrElse { throw ImageException("图片数据解码失败") }
                )
                continue
            }
            val url = item.optString("url")
            if (url.isNotBlank()) out.add(download(url))
        }
        return out
    }

    private fun download(url: String): ByteArray {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw ImageException("图片下载失败：HTTP $code")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (e: ImageException) {
            throw e
        } catch (e: Throwable) {
            throw ImageException("图片下载失败：${e.message}")
        }
    }

    private fun buildMultipart(
        boundary: String,
        fields: List<Pair<String, String>>,
        files: List<Triple<String, String, ByteArray>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val crlf = "\r\n"
        fields.forEach { (name, value) ->
            out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"$name\"$crlf$crlf".toByteArray(Charsets.UTF_8))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write(crlf.toByteArray(Charsets.UTF_8))
        }
        files.forEach { (field, filename, bytes) ->
            out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            out.write(
                "Content-Disposition: form-data; name=\"$field\"; filename=\"$filename\"$crlf"
                    .toByteArray(Charsets.UTF_8)
            )
            out.write("Content-Type: ${mimeOf(filename)}$crlf$crlf".toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.write(crlf.toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--$crlf".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun errorMessage(text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return text.take(180)
        val err = json.optJSONObject("error")
        val msg = err?.optString("message").orEmpty()
        if (msg.isNotBlank()) return msg.take(180)
        return json.optString("message").ifBlank { text.take(180) }
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrNull() ?: url

    private fun extOf(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/webp" -> ".webp"
        else -> ".png"
    }

    private fun mimeOf(filename: String): String = when {
        filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".webp", true) -> "image/webp"
        else -> "image/png"
    }

    private fun sniffExt(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "png"
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte()
        ) return "webp"
        return null
    }
}
