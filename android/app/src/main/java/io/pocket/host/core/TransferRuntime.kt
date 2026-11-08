package io.pocket.host.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 互传功能的统一入口：进程启动时拉起 HTTP 服务与 UDP 发现。 */
object TransferRuntime {

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val port = runCatching { TransferServer.start(context) }.getOrDefault(0)
        if (port > 0) {
            runCatching { TransferDiscovery.start(context, port) }
        }
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("running", TransferServer.port > 0)
        .put("port", TransferServer.port)
        .put("ip", TransferDiscovery.localIp() ?: "")
        .put("deviceId", TransferDiscovery.deviceId)
        .put("deviceName", TransferDiscovery.deviceName)
        .put("peers", TransferDiscovery.list().size)

    fun devicesJson(): JSONArray {
        val array = JSONArray()
        TransferDiscovery.list().forEach { array.put(it.toJson()) }
        return array
    }

    fun findPeer(id: String): Peer? = TransferDiscovery.list().firstOrNull { it.id == id }
}
