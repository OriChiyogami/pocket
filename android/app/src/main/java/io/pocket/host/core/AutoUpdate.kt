package io.pocket.host.core

import android.content.Context
import io.pocket.host.PocketApp
import java.io.File

/**
 * 启动时把电脑端上版本更高的小应用静默装好（只替换 www，数据保留）。
 *
 * 注意：宿主 APK 本身受 Android 限制，普通应用无法静默安装，只能下载后交给系统安装器。
 */
object AutoUpdate {

    private const val PREFS = "pocket"
    private const val K_ENABLED = "auto_sync_apps"
    private const val K_LAST = "last_lan_server"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, value)
            .apply()
    }

    /** 阻塞调用，需放在 IO 线程。返回这次自动更新的应用名。 */
    fun syncApps(context: Context): List<String> {
        if (!enabled(context)) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(K_LAST, null) ?: return emptyList()
        val server = LanClient.parseAddress(saved) ?: return emptyList()
        val remote = runCatching { LanClient.fetchApps(server) }.getOrElse { return emptyList() }
        if (remote.isEmpty()) return emptyList()

        val app = PocketApp.of(context)
        val installed = app.repository.apps.value
        val updated = mutableListOf<String>()

        remote.forEach { item ->
            val local = installed.firstOrNull { it.id == item.id } ?: return@forEach
            if (!newer(item.version, local.version)) return@forEach
            val tmp = File(context.cacheDir, "auto-${item.id}.pkt")
            runCatching {
                LanClient.downloadTo(server, item, tmp)
                app.installer.installFromZipFile(tmp, "lan-auto")
                updated.add(item.name)
            }
            tmp.delete()
        }
        return updated
    }

    private fun newer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(value: String): List<Int> =
        value.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
