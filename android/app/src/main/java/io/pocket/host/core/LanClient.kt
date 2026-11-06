package io.pocket.host.core

import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** 局域网里的电脑端服务。 */
data class LanServer(
    val name: String,
    val host: String,
    val port: Int
) {
    val display: String get() = "$host:$port"
}

data class HostInfo(
    val version: String,
    val versionCode: Int,
    val size: Long,
    val notes: String
)

data class RemoteApp(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val accent: String,
    val size: Long,
    val file: String,
    val hasIcon: Boolean
)

/**
 * 电脑端服务的客户端：UDP 广播发现 + HTTP 拉取。
 * 仅用于局域网导入，不参与应用运行时的网络策略。
 */
object LanClient {

    const val DEFAULT_PORT = 8570
    private const val DISCOVER_PORT = 8571
    private const val DISCOVER_PROBE = "POCKET_DISCOVER/1"

    fun discover(timeoutMs: Long = 2600, port: Int = DISCOVER_PORT): List<LanServer> {
        val found = linkedMapOf<String, LanServer>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 400
            val payload = DISCOVER_PROBE.toByteArray()
            val address = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(payload, payload.size, address, port))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (json.optString("service") != "pocket") continue
                    val host = json.optString("host").ifEmpty { packet.address.hostAddress }
                    val httpPort = json.optInt("port", DEFAULT_PORT)
                    val name = json.optString("name", "我的电脑")
                    found["$host:$httpPort"] = LanServer(name, host, httpPort)
                } catch (e: SocketTimeoutException) {
                    // 继续等
                }
            }
        } finally {
            runCatching { socket.close() }
        }
        return found.values.toList()
    }

    fun fetchApps(server: LanServer, timeoutMs: Int = 6000): List<RemoteApp> {
        val body = httpGet("http://${server.host}:${server.port}/api/apps", timeoutMs)
        val json = JSONObject(body)
        val array = json.optJSONArray("apps") ?: return emptyList()
        val list = mutableListOf<RemoteApp>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            list.add(
                RemoteApp(
                    id = o.optString("id"),
                    name = o.optString("name", "未命名"),
                    version = o.optString("version", "1.0.0"),
                    description = o.optString("description"),
                    accent = o.optString("accent", "#7C5CFF"),
                    size = o.optLong("size", 0L),
                    file = o.optString("file"),
                    hasIcon = o.optBoolean("hasIcon", false)
                )
            )
        }
        return list
    }

    /** 读取电脑端宿主 APK 版本；电脑端没放 APK 时返回 null。 */
    fun fetchHost(server: LanServer, timeoutMs: Int = 5000): HostInfo? {
        val body = runCatching { httpGet("http://${server.host}:${server.port}/api/host", timeoutMs) }.getOrNull()
            ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.optBoolean("ok", false)) return null
        return HostInfo(
            version = json.optString("version", "1.0.0"),
            versionCode = json.optInt("versionCode", 0),
            size = json.optLong("size", 0L),
            notes = json.optString("notes", "")
        )
    }

    fun downloadHost(server: LanServer, dest: File, timeoutMs: Int = 60000): File {
        val url = URL("http://${server.host}:${server.port}/host.apk")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = timeoutMs
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw PackageException("下载失败（HTTP ${connection.responseCode}）")
            }
            connection.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (!dest.isFile || dest.length() == 0L) throw PackageException("下载的安装包为空")
        return dest
    }

    fun fetchIcon(server: LanServer, id: String, timeoutMs: Int = 4000): ByteArray? {
        return runCatching { httpGetBytes("http://${server.host}:${server.port}/icon/${encode(id)}", timeoutMs) }.getOrNull()
    }

    fun downloadTo(server: LanServer, app: RemoteApp, dest: File, timeoutMs: Int = 30000): File {
        val url = URL("http://${server.host}:${server.port}/apps/${encode(app.file)}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw PackageException("下载失败（HTTP ${connection.responseCode}）")
            }
            connection.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (!dest.isFile || dest.length() == 0L) throw PackageException("下载的文件为空")
        return dest
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = timeoutMs
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw PackageException("电脑端返回 HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGetBytes(url: String, timeoutMs: Int): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = timeoutMs
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw PackageException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** 手动输入 "ip" 或 "ip:端口" */
    fun parseAddress(input: String): LanServer? {
        val text = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (text.isEmpty()) return null
        val parts = text.split(":")
        val host = parts[0].trim()
        if (host.isEmpty()) return null
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        return LanServer("电脑", host, port)
    }
}
