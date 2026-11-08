package io.pocket.host.core

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** 局域网里发现到的一台设备（手机或电脑）。 */
data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
    val lastSeen: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("platform", platform)
        .put("host", host)
        .put("port", port)
        .put("lastSeen", lastSeen)
}

/**
 * UDP 8571 设备发现：
 *  - 收到 POCKET_DISCOVER/1 立即回自己的信息
 *  - 每 5 秒广播一次自己的信息（电脑端据此列出手机）
 *  - 收到其它设备的 JSON 就记入设备表，20 秒过期
 */
object TransferDiscovery {

    const val DISCOVER_PORT = 8571
    private const val PROBE = "POCKET_DISCOVER/1"
    private const val PREFS = "pocket"
    private const val K_ID = "transfer_device_id"
    private const val EXPIRE_MS = 25_000L

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false

    @Volatile var transferPort: Int = 0
        private set
    @Volatile var deviceId: String = ""
        private set
    @Volatile var deviceName: String = ""
        private set

    private val peers = ConcurrentHashMap<String, Peer>()

    fun start(context: Context, port: Int) {
        if (running) {
            transferPort = port
            return
        }
        transferPort = port
        deviceId = ensureId(context)
        deviceName = Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android"
        running = true
        loop = thread(start = true, isDaemon = true, name = "pocket-discovery") {
            runLoop()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        peers.clear()
    }

    fun list(): List<Peer> {
        val now = System.currentTimeMillis()
        peers.entries.removeAll { now - it.value.lastSeen > EXPIRE_MS }
        return peers.values.sortedBy { it.name.lowercase() }
    }

    fun infoJson(): JSONObject = JSONObject()
        .put("id", deviceId)
        .put("name", deviceName)
        .put("platform", "android")
        .put("port", transferPort)
        .put("version", 1)

    /** 本机在局域网里的 IPv4，优先 wlan。 */
    fun localIp(): String? {
        return runCatching {
            val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
            val addresses = candidates
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> Collections.list(nif.inetAddresses).map { nif.name to it } }
            val usable = addresses.filter { (_, addr) ->
                addr is java.net.Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
            }
            val wifi = usable.firstOrNull { (name, _) -> name.startsWith("wlan") }
            (wifi ?: usable.firstOrNull())?.second?.hostAddress
        }.getOrNull()
    }

    // ---------------------------------------------------------------- loop

    private fun runLoop() {
        val s = openSocket() ?: run {
            running = false
            return
        }
        socket = s
        val buffer = ByteArray(4096)
        var lastAnnounce = 0L
        while (running) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastAnnounce > 5000) {
                    broadcast(s)
                    lastAnnounce = now
                }
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                when {
                    text == PROBE -> reply(s, packet)
                    text.startsWith("{") -> handleJson(text, packet.address.hostAddress)
                }
            } catch (e: SocketTimeoutException) {
                // 继续下一轮，顺便处理广播
            } catch (e: Throwable) {
                if (!running) break
                runCatching { Thread.sleep(300) }
            }
        }
        runCatching { s.close() }
    }

    private fun openSocket(): DatagramSocket? {
        runCatching {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(DISCOVER_PORT))
            s.broadcast = true
            s.soTimeout = 2000
            return s
        }
        runCatching {
            val s = DatagramSocket(DISCOVER_PORT)
            s.broadcast = true
            s.soTimeout = 2000
            return s
        }
        return null
    }

    private fun payload(): ByteArray {
        val json = infoJson()
        localIp()?.let { json.put("host", it) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun broadcast(s: DatagramSocket) {
        runCatching {
            val data = payload()
            s.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), DISCOVER_PORT))
        }
    }

    private fun reply(s: DatagramSocket, packet: DatagramPacket) {
        runCatching {
            val data = payload()
            s.send(DatagramPacket(data, data.size, packet.address, packet.port))
        }
    }

    private fun handleJson(text: String, fromHost: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.optString("service") != "pocket") return
        val id = json.optString("id")
        if (id.isBlank() || id == deviceId) return
        val port = json.optInt("transferPort", json.optInt("port", 0))
        if (port <= 0) return
        peers[id] = Peer(
            id = id,
            name = json.optString("name", "未命名设备"),
            platform = json.optString("platform", "unknown"),
            host = json.optString("host").ifBlank { fromHost },
            port = port,
            lastSeen = System.currentTimeMillis()
        )
    }

    private fun ensureId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(K_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = "d" + UUID.randomUUID().toString().replace("-", "").take(15)
        prefs.edit().putString(K_ID, id).apply()
        return id
    }
}
