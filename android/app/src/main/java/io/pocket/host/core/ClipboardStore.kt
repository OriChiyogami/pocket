package io.pocket.host.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一条剪贴板历史。source 为 phone（手机复制）或 pc（电脑复制）。 */
data class ClipboardEntry(
    val id: String,
    val text: String,
    val time: Long,
    val source: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("time", time)
        .put("source", source)
}

/**
 * 剪贴板历史：files/clipboard.json，最多 50 条，按文字去重。
 * 手机与电脑的历史通过局域网互相拉取后合并到这里，因此列表是双方的并集。
 */
object ClipboardStore {

    private const val FILE_NAME = "clipboard.json"
    private const val MAX_ITEMS = 50
    private const val MAX_TEXT = 100_000

    private val lock = Any()

    fun list(context: Context): List<ClipboardEntry> = synchronized(lock) { load(context) }

    fun add(
        context: Context,
        text: String,
        source: String,
        time: Long = 0L
    ): ClipboardEntry? = synchronized(lock) {
        val clean = normalize(text) ?: return@synchronized null
        val stamp = if (time > 0) time else System.currentTimeMillis()
        val all = load(context).toMutableList()
        val index = all.indexOfFirst { it.text == clean }
        val entry = if (index >= 0) {
            val old = all.removeAt(index)
            old.copy(
                time = maxOf(old.time, stamp),
                source = if (stamp >= old.time) source else old.source
            )
        } else {
            ClipboardEntry(newId(), clean, stamp, source)
        }
        all.add(0, entry)
        val trimmed = all.sortedByDescending { it.time }.take(MAX_ITEMS)
        save(context, trimmed)
        trimmed.firstOrNull { it.text == clean }
    }

    /** 合并一批记录（来自电脑），返回新增条数。 */
    fun merge(context: Context, incoming: List<ClipboardEntry>): Int = synchronized(lock) {
        if (incoming.isEmpty()) return 0
        val all = load(context).toMutableList()
        var added = 0
        incoming.forEach { item ->
            val clean = normalize(item.text) ?: return@forEach
            val index = all.indexOfFirst { it.text == clean }
            if (index >= 0) {
                val old = all[index]
                val stamp = maxOf(old.time, item.time)
                all[index] = old.copy(
                    time = stamp,
                    source = if (item.time >= old.time) item.source else old.source
                )
            } else {
                all.add(ClipboardEntry(newId(), clean, item.time, item.source))
                added++
            }
        }
        save(context, all.sortedByDescending { it.time }.take(MAX_ITEMS))
        added
    }

    fun remove(context: Context, id: String): Boolean = synchronized(lock) {
        val all = load(context).toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) save(context, all)
        removed
    }

    fun clear(context: Context) = synchronized(lock) {
        save(context, emptyList())
    }

    // ---------------------------------------------------------------- 文件

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): List<ClipboardEntry> {
        val file = file(context)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<ClipboardEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val content = normalize(o.optString("text")) ?: continue
            list.add(
                ClipboardEntry(
                    id = o.optString("id").ifBlank { newId() },
                    text = content,
                    time = o.optLong("time").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    source = o.optString("source", "phone")
                )
            )
        }
        return list.sortedByDescending { it.time }.take(MAX_ITEMS)
    }

    private fun save(context: Context, items: List<ClipboardEntry>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        val file = file(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        runCatching {
            tmp.writeText(JSONObject().put("items", array).toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun normalize(raw: String): String? {
        if (raw.isBlank()) return null
        return if (raw.length > MAX_TEXT) raw.substring(0, MAX_TEXT) else raw
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
