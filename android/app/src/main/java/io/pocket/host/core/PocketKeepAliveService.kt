package io.pocket.host.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.pocket.host.MainActivity

/**
 * 前台保活服务：手机退到后台后，保持口袋进程、HTTP 互传服务和 UDP 发现存活。
 * 实时剪贴板开启时启动；关闭开关时停止。
 */
class PocketKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val clipboard by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        scheduleClipboardRead()
    }
    private var overlayView: View? = null
    private var reading = false
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (e: Throwable) {
            startForeground(NOTIFICATION_ID, notification)
        }
        runCatching { clipboard.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { TransferRuntime.start(this) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { clipboard.removePrimaryClipChangedListener(clipListener) }
        overlayView?.let { view ->
            runCatching {
                val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                manager.removeView(view)
            }
        }
        overlayView = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /**
     * 别的应用复制时，本应用没有焦点，Android 10+ 会直接返回 null。
     * 这里临时加一个 1×1 的可聚焦透明悬浮窗抢一下焦点，读完立刻移除。
     * 需要「显示在其他应用上层」权限。
     */
    private fun scheduleClipboardRead() {
        if (!ClipboardMonitor.isEnabled(this)) return
        handler.post { readWithFocus() }
    }

    private fun readWithFocus() {
        if (reading) return
        val direct = readClipboard()
        if (!direct.isNullOrBlank()) {
            store(direct)
            return
        }
        if (!ClipboardMonitor.isOverlayAllowed(this)) return

        reading = true
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            manager.addView(view, params)
            overlayView = view
        } catch (e: Throwable) {
            reading = false
            return
        }
        handler.postDelayed({
            val text = readClipboard()
            runCatching {
                if (overlayView != null) manager.removeView(overlayView)
            }
            overlayView = null
            reading = false
            if (!text.isNullOrBlank()) store(text)
        }, 90)
    }

    private fun readClipboard(): String? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun store(text: String) {
        if (text == lastText) return
        lastText = text
        val entry = ClipboardStore.add(this, text, "phone") ?: return
        TransferEvents.emit("clipboard.changed", entry.toJson())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "口袋后台同步",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持手机和电脑连接，后台同步剪贴板"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("口袋正在保持连接")
            .setContentText("手机复制的文字会在后台自动同步到电脑")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pocket_keepalive"
        private const val NOTIFICATION_ID = 1401

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, PocketKeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, PocketKeepAliveService::class.java)) }
        }
    }
}
