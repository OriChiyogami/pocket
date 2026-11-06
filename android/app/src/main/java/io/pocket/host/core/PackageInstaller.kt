package io.pocket.host.core

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** 把 .pkt（zip）或单文件 HTML 变成可以长期保存的已安装应用。 */
class PackageInstaller(
    private val context: Context,
    private val repo: PackageRepository
) {

    fun installFromUri(uri: Uri, source: String): InstalledApp {
        val tmp = File(repo.tempDir(), "import-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw PackageException("无法读取所选文件")
            return installFromZipFile(tmp, source)
        } finally {
            tmp.delete()
        }
    }

    fun installFromStream(input: InputStream, source: String): InstalledApp {
        val tmp = File(repo.tempDir(), "import-${UUID.randomUUID()}.zip")
        try {
            tmp.outputStream().use { input.copyTo(it) }
            return installFromZipFile(tmp, source)
        } finally {
            tmp.delete()
        }
    }

    fun installFromZipFile(zipFile: File, source: String): InstalledApp {
        val unpackDir = File(repo.tempDir(), "unpack-${UUID.randomUUID()}")
        try {
            SafeZip.extract(zipFile, unpackDir)

            val root = findPackageRoot(unpackDir)
                ?: throw PackageException("包内缺少 manifest.json")
            val manifestFile = File(root, "manifest.json")
            val manifest = AppManifest.parse(manifestFile.readText())

            val entryFile = File(root, manifest.entry)
            if (!entryFile.isFile) {
                throw PackageException("入口文件不存在：${manifest.entry}")
            }

            val icon = resolveIcon(root, manifest.icon)
            val size = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

            // 同名应用复用原 id：即使新包换了 id，也只替换 www（功能），
            // data 目录和 WebView origin 都不变，历史数据得以保留。
            val sameName = repo.apps.value.firstOrNull {
                it.name.replace(" ", "") == manifest.name.replace(" ", "")
            }
            val targetId = if (repo.get(manifest.id) != null) manifest.id
            else sameName?.id ?: manifest.id

            val www = repo.wwwDir(targetId)
            if (www.exists()) www.deleteRecursively()
            www.parentFile?.mkdirs()
            moveDirectory(root, www)

            val previous = repo.get(targetId)
            val app = InstalledApp(
                id = targetId,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                accent = manifest.accent,
                entry = manifest.entry,
                orientation = manifest.orientation,
                statusBar = manifest.statusBar,
                fullscreen = manifest.fullscreen,
                edgeToEdge = manifest.edgeToEdge,
                background = manifest.background,
                permissions = manifest.permissions,
                networkWhitelist = manifest.networkWhitelist,
                installedAt = previous?.installedAt ?: System.currentTimeMillis(),
                lastUsedAt = previous?.lastUsedAt ?: 0L,
                launchCount = previous?.launchCount ?: 0,
                totalForegroundMs = previous?.totalForegroundMs ?: 0L,
                sizeBytes = size,
                source = source,
                iconFile = icon,
                folder = manifest.folder ?: previous?.folder,
                extraHosts = previous?.extraHosts ?: emptyList(),
                wipeRequested = false
            )
            repo.upsert(app)
            return app
        } finally {
            unpackDir.deleteRecursively()
        }
    }

    /** 单文件 HTML 直接保存为本地应用。 */
    fun installFromHtml(html: String, name: String, source: String = "html"): InstalledApp {
        val displayName = name.trim().ifEmpty { "小玩意" }.take(40)
        val slug = displayName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(18)
            .ifEmpty { "app" }
        val hash = sha256(html).take(6)
        // 同名 HTML 视为一次更新：沿用原 id，保证 WebView origin 不变、数据不丢
        val previous = repo.apps.value.firstOrNull {
            it.name.replace(" ", "") == displayName.replace(" ", "")
        } ?: repo.get("local.$slug-$hash")
        val id = previous?.id ?: "local.$slug"

        val www = repo.wwwDir(id)
        www.deleteRecursively()
        www.mkdirs()
        File(www, "index.html").writeText(html)

        val accentPalette = listOf("#7C5CFF", "#FF6B9D", "#22B8CF", "#FF9F43", "#2ED573", "#A55EEA")
        val accent = previous?.accent
            ?: accentPalette[(hash.hashCode() and Int.MAX_VALUE) % accentPalette.size]

        val app = InstalledApp(
            id = id,
            name = displayName,
            version = bumpVersion(previous?.version),
            description = "由 HTML 片段创建",
            accent = accent,
            entry = "index.html",
            orientation = "auto",
            statusBar = "auto",
            fullscreen = false,
            edgeToEdge = false,
            background = "#FFFFFFFF",
            permissions = setOf("storage", "http", "clipboard", "share", "haptic", "external"),
            networkWhitelist = emptyList(),
            installedAt = previous?.installedAt ?: System.currentTimeMillis(),
            lastUsedAt = previous?.lastUsedAt ?: 0L,
            launchCount = previous?.launchCount ?: 0,
            totalForegroundMs = previous?.totalForegroundMs ?: 0L,
            sizeBytes = html.toByteArray().size.toLong(),
            source = source,
            iconFile = null,
            folder = previous?.folder,
            extraHosts = previous?.extraHosts ?: emptyList(),
            wipeRequested = false
        )
        repo.upsert(app)
        return app
    }

    private fun bumpVersion(current: String?): String {
        if (current.isNullOrBlank()) return "1.0.0"
        val parts = current.split('.')
        val last = (parts.lastOrNull()?.toIntOrNull() ?: 0) + 1
        return if (parts.size >= 2) {
            (parts.dropLast(1) + last.toString()).joinToString(".")
        } else {
            "$current.$last"
        }
    }

    fun installBuiltin(assetName: String): InstalledApp? {
        return try {
            context.assets.open("builtin/$assetName").use { installFromStream(it, "builtin") }
        } catch (e: Exception) {
            null
        }
    }

    fun ensureBuiltins(vararg assetNames: String) {
        if (repo.builtinsInstalled) return
        assetNames.forEach { installBuiltin(it) }
        repo.markBuiltinsInstalled()
    }

    private fun findPackageRoot(unpackDir: File): File? {
        if (File(unpackDir, "manifest.json").isFile) return unpackDir
        val children = unpackDir.listFiles()?.filter { it.isDirectory } ?: return null
        if (children.size == 1 && File(children[0], "manifest.json").isFile) return children[0]
        return children.firstOrNull { File(it, "manifest.json").isFile }
    }

    private fun resolveIcon(root: File, declared: String?): String? {
        val candidates = listOfNotNull(
            declared,
            "icon.png",
            "icon.webp",
            "icon.jpg",
            "icon.svg"
        )
        for (candidate in candidates) {
            val normalized = candidate.replace('\\', '/').removePrefix("./").removePrefix("/")
            if (normalized.contains("..")) continue
            if (File(root, normalized).isFile) return normalized
        }
        return null
    }

    private fun moveDirectory(source: File, dest: File) {
        if (source.renameTo(dest)) return
        dest.mkdirs()
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).path
            val target = File(dest, relative)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
        source.deleteRecursively()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

internal fun JSONObject.optStringList(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }
}
