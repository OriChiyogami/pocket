package io.pocket.host.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject

/**
 * 「实时剪贴板」开关与服务状态。
 *
 * Android 10 起，后台应用不能直接读剪贴板；这里用无障碍服务在用户选中/复制文字时
 * 优先读取剪贴板，读不到时退回到无障碍节点里的选中文本。用户必须手动开启系统无障碍权限。
 */
object ClipboardMonitor {

    private const val PREFS = "pocket_clipboard_monitor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONNECTED = "connected"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) PocketKeepAliveService.start(context)
        else PocketKeepAliveService.stop(context)
    }

    fun isServiceConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONNECTED, false)

    fun setServiceConnected(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, connected).apply()
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PocketClipboardService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { item ->
            ComponentName.unflattenFromString(item) == expected
        }
    }

    fun isOverlayAllowed(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun status(context: Context): JSONObject = JSONObject()
        .put("enabled", isEnabled(context))
        .put("accessibility", isAccessibilityEnabled(context))
        .put("overlay", isOverlayAllowed(context))
        .put("connected", isServiceConnected(context))
}
