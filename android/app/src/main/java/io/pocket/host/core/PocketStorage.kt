package io.pocket.host.core

import org.json.JSONObject
import java.io.File

/**
 * SDK 的 pocket.storage 落盘实现：每个应用独立 JSON 文件，原子写入。
 * 值以 JSON 文本形式保存，读取时由 JS 侧 JSON.parse。
 */
class PocketStorage(private val file: File) {

    private val lock = Any()

    fun get(key: String): String? = synchronized(lock) {
        val obj = read()
        if (!obj.has(key)) null else obj.optString(key)
    }

    fun set(key: String, rawJson: String) {
        if (key.length > 128) throw BridgeException("E_INVALID_ARG", "storage key 过长")
        if (rawJson.length > 256 * 1024) throw BridgeException("E_QUOTA", "单个值超过 256KB")
        synchronized(lock) {
            val obj = read()
            obj.put(key, rawJson)
            write(obj)
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            val obj = read()
            obj.remove(key)
            write(obj)
        }
    }

    fun clear() {
        synchronized(lock) {
            write(JSONObject())
        }
    }

    fun keys(): List<String> = synchronized(lock) {
        val obj = read()
        obj.keys().asSequence().toList()
    }

    fun bytes(): Long = if (file.exists()) file.length() else 0L

    private fun read(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun write(obj: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(obj.toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }
}
