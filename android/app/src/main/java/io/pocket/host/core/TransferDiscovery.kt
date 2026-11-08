package io.pocket.host.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
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
 * UDP 8571 设备发现。
 *
 * 广播在部分环境（Windows 移动热点、开启 AP 隔离的路由器）不互通，所以这里同时做：
 *  - 回应对端的 POCKET_DISCOVER/1 探测
 *  - 每 5 秒向 255.255.255.255、本网段广播地址、默认网关、记住过的设备 **单播** 自己的信息和探测包
 *
 * 手机连电脑热点时，默认网关就是电脑，单播能通，于是双方都能互相发现。
 */
object TransferDiscovery {

    const val DISCOVER_PORT = 8571
    private const val PROBE = "POCKET_DISCOVER/1"
    private const val PREFS = "pocket"
    private const val K_ID = "transfer_device_id"
    private const val K_PEERS = "transfer_known_hosts"
    private const val MAX_KNOWN = 24
    private const val EXPIRE_MS = 25_000L

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false
    @Volatile private var appContext: Context? = null
    @Volatile private var gateway: String? = null
    @Volatile private var subnetBroadcast: String? = null

    @Volatile var transferPort: Int = 0
        private set
    @Volatile var deviceId: String = ""
        private set
    @Volatile var deviceName: String = ""
        private set

    private val peers = ConcurrentHashMap<String, Peer>()
    private val known = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())

    fun start(context: Context, port: Int) {
        if (running) {
            transferPort = port
            return
        }
        transferPort = port
        appContext = context.applicationContext
        deviceId = ensureId(context)
        deviceName = Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android"
        loadKnown(context)
        refreshHints(context)
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
        .put("service", "pocket")
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
                addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
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
                    refreshHints(appContext)
                    announceAndProbe(s)
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

    /**
     * 每 5 秒一次：向所有能想到的目标同时发「自我介绍」和「探测包」。
     * 自我介绍让电脑端登记手机；探测包让电脑端回一份自己的信息。
     */
    private fun announceAndProbe(s: DatagramSocket) {
        val data = payload()
        val probe = PROBE.toByteArray(Charsets.UTF_8)
        val targets = linkedSetOf<String>()
        targets.add("255.255.255.255")
        subnetBroadcast?.let { targets.add(it) }
        gateway?.let { targets.add(it) }
        known.keys.forEach { targets.add(it) }

        targets.forEach { host ->
            if (host.isBlank()) return@forEach
            sendTo(s, data, host)
            sendTo(s, probe, host)
        }
    }

    private fun sendTo(s: DatagramSocket, data: ByteArray, host: String) {
        runCatching {
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(host), DISCOVER_PORT))
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
        val host = json.optString("host").ifBlank { fromHost }
        peers[id] = Peer(
            id = id,
            name = json.optString("name", "未命名设备"),
            platform = json.optString("platform", "unknown"),
            host = host,
            port = port,
            lastSeen = System.currentTimeMillis()
        )
        rememberHost(host)
    }

    // ---------------------------------------------------------------- 网络提示

    /** 默认网关与所在网段的广播地址：热点场景下网关就是电脑。 */
    private fun refreshHints(context: Context?) {
        val ctx = context ?: return
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.getLinkProperties(cm.activeNetwork) ?: return
            gateway = props.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress

            val link = props.linkAddresses?.firstOrNull { it.address is Inet4Address }
            val raw = (link?.address as? Inet4Address)?.address
            if (raw != null && raw.size == 4) {
                val ip = ((raw[0].toLong() and 0xFF) shl 24) or
                    ((raw[1].toLong() and 0xFF) shl 16) or
                    ((raw[2].toLong() and 0xFF) shl 8) or
                    (raw[3].toLong() and 0xFF)
                val prefix = (link?.prefixLength ?: 24).coerceIn(0, 32)
                val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val broadcast = (ip or (mask.inv() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
                subnetBroadcast = "${(broadcast ushr 24) and 0xFF}." +
                    "${(broadcast ushr 16) and 0xFF}." +
                    "${(broadcast ushr 8) and 0xFF}." +
                    "${broadcast and 0xFF}"
            }
        }
    }

    // ---------------------------------------------------------------- 记住设备

    private fun rememberHost(host: String) {
        if (host.isBlank()) return
        val isNew = !known.containsKey(host)
        known[host] = System.currentTimeMillis()
        if (known.size > MAX_KNOWN) {
            known.keys.firstOrNull()?.let { known.remove(it) }
        }
        if (isNew) persistKnown()
    }

    private fun loadKnown(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_PEERS, "").orEmpty()
        raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach {
            known[it] = 0L
        }
    }

    private fun persistKnown() {
        val ctx = appContext ?: return
        val value = known.keys.joinToString("\n")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_PEERS, value)
            .apply()
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
