package io.pocket.host.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 通过口袋互传收发的文件。与系统文件、应用 www 目录完全分开。
 *
 * files/transfer/index.json 记录元数据；实际文件在 inbox/ 与 sent/ 下按日期分目录。
 */
data class TransferEntry(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val direction: String,
    val peer: String,
    val time: Long,
    val relPath: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("size", size)
        .put("mime", mime)
        .put("direction", direction)
        .put("peer", peer)
        .put("time", time)
}

object TransferStore {

    private const val ROOT = "transfer"
    private const val INDEX = "index.json"
    private const val MAX_NAME = 120

    private val lock = Any()

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun inbox(context: Context): File = File(root(context), "inbox").apply { mkdirs() }

    fun sentDir(context: Context): File = File(root(context), "sent").apply { mkdirs() }

    fun list(context: Context, direction: String = ""): List<TransferEntry> = synchronized(lock) {
        val all = load(context).sortedByDescending { it.time }
        if (direction.isBlank()) all else all.filter { it.direction == direction }
    }

    fun get(context: Context, id: String): TransferEntry? =
        synchronized(lock) { load(context).firstOrNull { it.id == id } }

    fun fileOf(context: Context, id: String): File? {
        val entry = get(context, id) ?: return null
        val file = File(root(context), entry.relPath)
        return if (file.isFile) file else null
    }

    /** 为一次上传准备目标文件：inbox/yyyy-MM-dd/名字 */
    fun newIncomingFile(context: Context, name: String): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(inbox(context), day).apply { mkdirs() }
        return File(dir, uniqueName(dir, name))
    }

    fun newOutgoingFile(context: Context, name: String): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(sentDir(context), day).apply { mkdirs() }
        return File(dir, uniqueName(dir, name))
    }

    fun add(
        context: Context,
        file: File,
        name: String,
        mime: String,
        direction: String,
        peer: String
    ): TransferEntry = synchronized(lock) {
        val entry = TransferEntry(
            id = UUID.randomUUID().toString().replace("-", ""),
            name = name,
            size = file.length(),
            mime = mime,
            direction = direction,
            peer = peer,
            time = System.currentTimeMillis(),
            relPath = file.relativeTo(root(context)).path.replace('\\', '/')
        )
        val all = load(context).toMutableList()
        all.add(0, entry)
        save(context, all)
        entry
    }

    fun delete(context: Context, id: String): Boolean = synchronized(lock) {
        val all = load(context).toMutableList()
        val entry = all.firstOrNull { it.id == id } ?: return@synchronized false
        runCatching { File(root(context), entry.relPath).delete() }
        all.remove(entry)
        save(context, all)
        true
    }

    fun rename(context: Context, id: String, newName: String): TransferEntry? = synchronized(lock) {
        val all = load(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val entry = all[index]
        val source = File(root(context), entry.relPath)
        if (!source.isFile) return@synchronized null
        val target = File(source.parentFile, uniqueName(source.parentFile ?: root(context), newName))
        if (!source.renameTo(target)) return@synchronized null

        val updated = entry.copy(
            name = target.name,
            relPath = target.relativeTo(root(context)).path.replace('\\', '/')
        )
        all[index] = updated
        save(context, all)
        updated
    }

    /** 生成不重复且安全的文件名 */
    fun uniqueName(dir: File, raw: String): String {
        val safe = sanitize(raw)
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var candidate = safe
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "$stem($i)$ext"
            i++
        }
        return candidate
    }

    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\u0000-\\u001F]"), "")
            .trim('.', ' ')
        val text = cleaned.ifBlank { "file" }
        if (text.length <= MAX_NAME) return text
        val dot = text.lastIndexOf('.')
        return if (dot in 1 until MAX_NAME - 12) {
            text.substring(0, dot).take(MAX_NAME - 8) + text.substring(dot)
        } else {
            text.take(MAX_NAME)
        }
    }

    // ---------------------------------------------------------------- index

    private fun load(context: Context): List<TransferEntry> {
        val file = File(root(context), INDEX)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("files") ?: return emptyList()
        val list = mutableListOf<TransferEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val entry = TransferEntry(
                id = o.optString("id"),
                name = o.optString("name"),
                size = o.optLong("size"),
                mime = o.optString("mime", "application/octet-stream"),
                direction = o.optString("direction", "in"),
                peer = o.optString("peer"),
                time = o.optLong("time"),
                relPath = o.optString("relPath")
            )
            if (entry.id.isBlank() || entry.relPath.isBlank()) continue
            // 文件被手动删掉时，索引也同步清掉
            if (File(root(context), entry.relPath).isFile) list.add(entry)
        }
        return list
    }

    private fun save(context: Context, entries: List<TransferEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson().put("relPath", it.relPath)) }
        val json = JSONObject().put("files", array)
        val file = File(root(context), INDEX)
        file.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
