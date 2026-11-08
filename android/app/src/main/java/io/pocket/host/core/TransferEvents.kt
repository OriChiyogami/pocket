package io.pocket.host.core

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** 互传事件总线：服务端/发送端产生的事件广播给所有打开着的页面。 */
object TransferEvents {

    private val listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    fun add(listener: (String, JSONObject) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: (String, JSONObject) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(name: String, payload: JSONObject) {
        listeners.forEach { listener ->
            runCatching { listener(name, payload) }
        }
    }
}
