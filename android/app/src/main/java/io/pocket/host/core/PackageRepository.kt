package io.pocket.host.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/** 已安装应用索引，落盘为 files/apps.json，进程重启后自动恢复。 */
class PackageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val appsDir = File(appContext.filesDir, "apps")
    private val indexFile = File(appContext.filesDir, "apps.json")
    private val tmpDir = File(appContext.filesDir, "tmp")
    private val prefs = appContext.getSharedPreferences("pocket_host", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    init {
        appsDir.mkdirs()
        tmpDir.mkdirs()
        _apps.value = load()
        _folders.value = loadFolders()
    }

    val builtinsInstalled: Boolean
        get() = prefs.getBoolean("builtins_v1", false)

    fun markBuiltinsInstalled() {
        prefs.edit().putBoolean("builtins_v1", true).apply()
    }

    fun get(id: String): InstalledApp? = _apps.value.firstOrNull { it.id == id }

    fun appDir(id: String): File = File(appsDir, id)

    fun wwwDir(id: String): File = File(appDir(id), "www")

    fun dataDir(id: String): File = File(appDir(id), "data")

    fun tempDir(): File = tmpDir

    fun iconFileOf(app: InstalledApp): File? =
        app.iconFile?.let { File(wwwDir(app.id), it) }?.takeIf { it.isFile }

    @Synchronized
    fun upsert(app: InstalledApp) {
        val current = _apps.value
        val existing = current.firstOrNull { it.id == app.id }
        val merged = if (existing != null) {
            app.copy(
                installedAt = existing.installedAt,
                lastUsedAt = existing.lastUsedAt,
                launchCount = existing.launchCount,
                totalForegroundMs = existing.totalForegroundMs
            )
        } else app
        _apps.value = if (existing != null) {
            current.map { if (it.id == app.id) merged else it }
        } else {
            current + merged
        }
        merged.folder?.let { ensureFolder(it) }
        persist()
    }

    @Synchronized
    fun moveToFolder(id: String, folder: String?) {
        val clean = folder?.trim()?.take(20)?.ifEmpty { null }
        if (clean != null) ensureFolder(clean)
        update(id) { it.copy(folder = clean) }
    }

    @Synchronized
    fun createFolder(name: String): String? {
        val clean = name.trim().take(20)
        if (clean.isEmpty()) return null
        ensureFolder(clean)
        return clean
    }

    @Synchronized
    fun renameFolder(old: String, next: String) {
        val clean = next.trim().take(20)
        if (clean.isEmpty() || clean == old) return
        _apps.value = _apps.value.map { if (it.folder == old) it.copy(folder = clean) else it }
        _folders.value = _folders.value.map { if (it == old) clean else it }.distinct()
        persist()
        persistFolders()
    }

    @Synchronized
    fun deleteFolder(name: String) {
        _apps.value = _apps.value.map { if (it.folder == name) it.copy(folder = null) else it }
        _folders.value = _folders.value.filterNot { it == name }
        persist()
        persistFolders()
    }

    private fun ensureFolder(name: String) {
        if (name in _folders.value) return
        _folders.value = _folders.value + name
        persistFolders()
    }

    private fun loadFolders(): List<String> {
        val raw = prefs.getString("folders", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
        }.getOrDefault(emptyList())
    }

    private fun persistFolders() {
        prefs.edit().putString("folders", JSONArray(_folders.value.toList()).toString()).apply()
    }

    @Synchronized
    fun update(id: String, transform: (InstalledApp) -> InstalledApp) {
        _apps.value = _apps.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _apps.value = _apps.value.filterNot { it.id == id }
        persist()
        appDir(id).deleteRecursively()
    }

    @Synchronized
    fun clearAppData(id: String) {
        dataDir(id).deleteRecursively()
        dataDir(id).mkdirs()
        update(id) { it.copy(wipeRequested = true) }
    }

    fun recordOpen(id: String) {
        update(id) { it.copy(lastUsedAt = System.currentTimeMillis(), launchCount = it.launchCount + 1) }
    }

    fun addForegroundTime(id: String, millis: Long) {
        if (millis <= 0) return
        update(id) { it.copy(lastUsedAt = System.currentTimeMillis(), totalForegroundMs = it.totalForegroundMs + millis) }
    }

    fun allowHost(id: String, host: String) {
        val app = get(id) ?: return
        if (host in app.networkWhitelist || host in app.extraHosts) return
        update(id) { it.copy(extraHosts = it.extraHosts + host.lowercase()) }
    }

    fun consumeWipeFlag(id: String): Boolean {
        val app = get(id) ?: return false
        if (!app.wipeRequested) return false
        update(id) { it.copy(wipeRequested = false) }
        return true
    }

    private fun load(): List<InstalledApp> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull {
                runCatching { InstalledApp.fromJson(arr.getJSONObject(it)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            _apps.value.forEach { arr.put(it.toJson()) }
            val tmp = File(indexFile.parentFile, "apps.json.tmp")
            tmp.writeText(arr.toString())
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }
}
