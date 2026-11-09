package io.pocket.host.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * manifest.json 的运行时模型。字段与 docs/02-插件包规范.md 保持一致。
 */
data class AppManifest(
    val id: String,
    val name: String,
    val version: String,
    val entry: String,
    val icon: String?,
    val description: String,
    val accent: String,
    val orientation: String,
    val statusBar: String,
    val fullscreen: Boolean,
    val edgeToEdge: Boolean,
    val background: String,
    val permissions: Set<String>,
    val networkWhitelist: List<String>,
    val folder: String? = null
) {

    /** 每个应用使用独立 host，从而拥有独立 origin，实现 localStorage/IndexedDB 自然隔离。 */
    val host: String = id.replace('_', '-').lowercase() + HOST_SUFFIX

    val origin: String = "https://$host"

    companion object {
        const val HOST_SUFFIX = ".pocket.local"

        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val COLOR_PATTERN = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

        val KNOWN_PERMISSIONS = setOf(
            "storage", "http", "clipboard", "share", "notify",
            "haptic", "file", "external"
        )

        fun parse(json: String): AppManifest {
            val o = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw PackageException("manifest.json 不是合法 JSON")
            }

            val id = o.optString("id").trim().lowercase()
            if (!ID_PATTERN.matches(id)) {
                throw PackageException("应用 id 非法：\"$id\"（仅允许小写字母、数字、点、下划线、连字符）")
            }

            val name = o.optString("name").trim().ifEmpty { id }.take(40)
            val version = o.optString("version").trim().ifEmpty { "1.0.0" }.take(24)
            val entry = o.optString("entry").trim().ifEmpty { "index.html" }

            val display = o.optJSONObject("display") ?: JSONObject()
            val orientation = display.optString("orientation", "auto").let {
                if (it == "portrait" || it == "landscape") it else "auto"
            }
            val statusBar = display.optString("statusBar", "auto").let {
                if (it == "light" || it == "dark" || it == "hidden") it else "auto"
            }
            val background = display.optString("background", "").takeIf {
                COLOR_PATTERN.matches(it)
            } ?: "#FFFFFFFF"

            val accent = o.optString("accent", "").takeIf { COLOR_PATTERN.matches(it) } ?: "#2F8BFF"

            val permissions = mutableSetOf<String>()
            o.optJSONArray("permissions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).trim().lowercase()
                    if (p in KNOWN_PERMISSIONS) permissions.add(p)
                }
            }

            val network = o.optJSONObject("network") ?: JSONObject()
            val whitelist = mutableListOf<String>()
            network.optJSONArray("whitelist")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i).trim().lowercase()
                    if (h.isNotEmpty() && h.length <= 200) whitelist.add(h)
                }
            }

            return AppManifest(
                id = id,
                name = name,
                version = version,
                entry = entry,
                icon = o.optString("icon").trim().ifEmpty { null },
                description = o.optString("description").trim().take(120),
                accent = accent,
                orientation = orientation,
                statusBar = statusBar,
                fullscreen = display.optBoolean("fullscreen", false),
                edgeToEdge = display.optBoolean("edgeToEdge", false),
                background = background,
                permissions = permissions,
                networkWhitelist = whitelist,
                folder = o.optString("folder").trim().take(20).ifEmpty { null }
            )
        }

        fun from(app: InstalledApp): AppManifest = AppManifest(
            id = app.id,
            name = app.name,
            version = app.version,
            entry = app.entry,
            icon = null,
            description = app.description,
            accent = app.accent,
            orientation = app.orientation,
            statusBar = app.statusBar,
            fullscreen = app.fullscreen,
            edgeToEdge = app.edgeToEdge,
            background = app.background,
            permissions = app.permissions,
            networkWhitelist = app.networkWhitelist,
            folder = app.folder
        )
    }
}

internal fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
