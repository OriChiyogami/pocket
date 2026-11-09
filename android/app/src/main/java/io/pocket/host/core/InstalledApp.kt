package io.pocket.host.core

import org.json.JSONArray
import org.json.JSONObject

/** 已安装应用的持久化记录（含使用统计）。 */
data class InstalledApp(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val accent: String,
    val entry: String,
    val orientation: String,
    val statusBar: String,
    val fullscreen: Boolean,
    val edgeToEdge: Boolean,
    val background: String,
    val permissions: Set<String>,
    val networkWhitelist: List<String>,
    val installedAt: Long,
    val lastUsedAt: Long,
    val launchCount: Int,
    val totalForegroundMs: Long,
    val sizeBytes: Long,
    val source: String,
    val iconFile: String?,
    val folder: String? = null,
    val extraHosts: List<String> = emptyList(),
    val wipeRequested: Boolean = false
) {

    val host: String = id.replace('_', '-').lowercase() + AppManifest.HOST_SUFFIX

    val origin: String = "https://$host"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("description", description)
        put("accent", accent)
        put("entry", entry)
        put("orientation", orientation)
        put("statusBar", statusBar)
        put("fullscreen", fullscreen)
        put("edgeToEdge", edgeToEdge)
        put("background", background)
        put("permissions", JSONArray(permissions.toList()))
        put("whitelist", JSONArray(networkWhitelist))
        put("installedAt", installedAt)
        put("lastUsedAt", lastUsedAt)
        put("launchCount", launchCount)
        put("totalForegroundMs", totalForegroundMs)
        put("sizeBytes", sizeBytes)
        put("source", source)
        put("iconFile", iconFile ?: JSONObject.NULL)
        put("folder", folder ?: JSONObject.NULL)
        put("extraHosts", JSONArray(extraHosts))
        put("wipeRequested", wipeRequested)
    }

    companion object {
        fun fromJson(o: JSONObject): InstalledApp = InstalledApp(
            id = o.getString("id"),
            name = o.optString("name", "未命名"),
            version = o.optString("version", "1.0.0"),
            description = o.optString("description", ""),
            accent = o.optString("accent", "#2F8BFF"),
            entry = o.optString("entry", "index.html"),
            orientation = o.optString("orientation", "auto"),
            statusBar = o.optString("statusBar", "auto"),
            fullscreen = o.optBoolean("fullscreen", false),
            edgeToEdge = o.optBoolean("edgeToEdge", false),
            background = o.optString("background", "#FFFFFFFF"),
            permissions = o.optJSONArray("permissions")?.toStringList()?.toSet() ?: emptySet(),
            networkWhitelist = o.optJSONArray("whitelist")?.toStringList() ?: emptyList(),
            installedAt = o.optLong("installedAt", System.currentTimeMillis()),
            lastUsedAt = o.optLong("lastUsedAt", 0L),
            launchCount = o.optInt("launchCount", 0),
            totalForegroundMs = o.optLong("totalForegroundMs", 0L),
            sizeBytes = o.optLong("sizeBytes", 0L),
            source = o.optString("source", "file"),
            iconFile = if (o.isNull("iconFile")) null else o.optString("iconFile").ifEmpty { null },
            folder = if (o.isNull("folder")) null else o.optString("folder").ifEmpty { null },
            extraHosts = o.optJSONArray("extraHosts")?.toStringList() ?: emptyList(),
            wipeRequested = o.optBoolean("wipeRequested", false)
        )
    }
}
