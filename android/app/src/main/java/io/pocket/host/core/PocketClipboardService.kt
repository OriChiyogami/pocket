package io.pocket.host.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 实时剪贴板监听。
 *
 * Android 10 起，只有「拥有输入焦点」的应用才能读剪贴板。
 * 分屏 / 另一个应用里复制时，口袋即使可见也没有焦点，直接读会拿到 null。
 *
 * 所以这里做两件事：
 * 1. 用无障碍事件识别「复制」动作（工具栏里的复制按钮、复制成功的 toast 等），
 *    这是全局事件，不受焦点限制；
 * 2. 识别到之后，临时加一个 1×1 的可聚焦透明悬浮窗（TYPE_ACCESSIBILITY_OVERLAY，
 *    不需要「显示在其他应用上层」权限），把焦点抢过来读一次，读完立刻移除。
 */
class PocketClipboardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastText = ""
    private var lastAt = 0L

    private var pendingSelection: String? = null
    private var pendingSelectionAt = 0L

    private var grabbing = false
    private var grabView: View? = null

    private val copyWords = arrayOf(
        "复制", "複製", "拷贝", "拷貝", "复制成功", "已复制", "已複製", "已拷贝"
    )
    private val copyToasts = arrayOf(
        "已复制", "已複製", "复制成功", "已拷贝", "copied", "copied to clipboard"
    )

    private val DEBUG = false

    private var lastSystemUiTrigger = 0L

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // 有焦点时系统会直接回调；没焦点时部分系统不回调，靠无障碍事件兜底
        requestRead(0, grab = true)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ClipboardMonitor.setServiceConnected(this, true)
        if (ClipboardMonitor.isEnabled(this)) {
            PocketKeepAliveService.start(this)
        }
        lastText = readClipboard().orEmpty()
        runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.addPrimaryClipChangedListener(clipListener)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!ClipboardMonitor.isEnabled(this)) return

        val own = event.packageName?.toString() == packageName
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (own) return
                val selected = selectedText(event)
                if (!selected.isNullOrBlank()) {
                    pendingSelection = selected
                    pendingSelectionAt = SystemClock.elapsedRealtime()
                }
                if (DEBUG) logEvent(event)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (own) return
                val label = clickLabel(event)
                if (DEBUG) logEvent(event)
                if (isCopyAction(label)) {
                    android.util.Log.d("PocketClip", "copy click: " + label.take(16))
                    // 复制按钮刚点下，给系统 80ms 把内容写进剪贴板
                    requestRead(80, grab = true)
                }
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (own) return
                val toast = event.text?.joinToString("")?.trim()
                if (DEBUG) logEvent(event)
                if (isCopyToast(toast)) requestRead(40, grab = true)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (DEBUG) logEvent(event)
                if (own) {
                    // 回到口袋自己（重新获得焦点），顺手读一次
                    requestRead(0, grab = false)
                    return
                }
                // Android 13+ 的系统剪贴板浮层：复制的最后兜底信号，
                // 只有刚选中过文字时才认为这是复制，避免通知栏/音量条误触发
                if (event.packageName?.toString() == "com.android.systemui" &&
                    SystemClock.elapsedRealtime() - pendingSelectionAt < 10_000 &&
                    SystemClock.elapsedRealtime() - lastSystemUiTrigger > 2_500
                ) {
                    lastSystemUiTrigger = SystemClock.elapsedRealtime()
                    requestRead(0, grab = true)
                }
            }
        }
    }

    private fun logEvent(event: AccessibilityEvent) {
        android.util.Log.d(
            "PocketClip",
            AccessibilityEvent.eventTypeToString(event.eventType) +
                " pkg=" + event.packageName +
                " cls=" + event.className +
                " txt=" + (event.text?.joinToString("|")?.take(40)) +
                " cd=" + event.contentDescription
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.removePrimaryClipChangedListener(clipListener)
        }
        removeGrabWindow()
        ClipboardMonitor.setServiceConnected(this, false)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ClipboardMonitor.setServiceConnected(this, false)
        return super.onUnbind(intent)
    }

    // ---------- 读取 ----------

    private fun requestRead(delayMs: Long, grab: Boolean) {
        if (!ClipboardMonitor.isEnabled(this)) return
        handler.postDelayed({
            if (!ClipboardMonitor.isEnabled(this)) return@postDelayed
            val direct = readClipboard()
            if (!direct.isNullOrBlank()) {
                store(direct)
                return@postDelayed
            }
            if (grab) {
                android.util.Log.d("PocketClip", "copy detected, direct=null, grabbing focus")
                grabAndRead()
            } else {
                fallbackSelection()
            }
        }, delayMs)
    }

    /** 临时抢焦点读一次：无障碍悬浮窗优先，失败再试普通悬浮窗（需要悬浮窗权限）。 */
    private fun grabAndRead() {
        if (grabbing) return
        if (!addGrabWindow()) {
            android.util.Log.w("PocketClip", "grab window failed")
            fallbackSelection()
            return
        }
        grabbing = true
        handler.postDelayed({
            val text = readClipboard()
            removeGrabWindow()
            grabbing = false
            android.util.Log.d("PocketClip", "grab read=" + (if (text == null) "null" else "ok"))
            if (!text.isNullOrBlank()) store(text) else fallbackSelection()
        }, 140)
    }

    private fun addGrabWindow(): Boolean {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val types = mutableListOf<Int>()
        types.add(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        if (Settings.canDrawOverlays(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                types.add(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                types.add(WindowManager.LayoutParams.TYPE_PHONE)
            }
        }
        for (type in types) {
            val view = View(this)
            val params = WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            val ok = runCatching {
                manager.addView(view, params)
                grabView = view
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun removeGrabWindow() {
        val view = grabView ?: return
        grabView = null
        runCatching {
            val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.removeView(view)
        }
    }

    private fun readClipboard(): String? = runCatching {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!manager.hasPrimaryClip()) return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun store(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 180) return
        lastAt = now
        if (text == lastText) return
        lastText = text
        val entry = ClipboardStore.add(this, text, "phone") ?: return
        TransferEvents.emit("clipboard.changed", entry.toJson())
    }

    /** 抢焦点也读不到时，退回本次选中的文本（至少不会丢）。 */
    private fun fallbackSelection() {
        val text = pendingSelection ?: return
        if (SystemClock.elapsedRealtime() - pendingSelectionAt > 8000) {
            pendingSelection = null
            return
        }
        pendingSelection = null
        store(text)
    }

    // ---------- 事件解析 ----------

    private fun clickLabel(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        event.text?.forEach { if (it != null) parts.add(it.toString()) }
        event.contentDescription?.let { parts.add(it.toString()) }
        runCatching {
            val node: AccessibilityNodeInfo? = event.source
            if (node != null) {
                node.text?.let { parts.add(it.toString()) }
                node.contentDescription?.let { parts.add(it.toString()) }
            }
        }
        return parts.joinToString(" ").trim()
    }

    private fun isCopyAction(label: String): Boolean {
        if (label.isBlank() || label.length > 48) return false
        val lower = label.lowercase()
        if (copyWords.any { lower.contains(it) }) return true
        // 英文环境：Copy / Copied / Copy text 之类，按单词匹配，避免误伤 copyright
        return lower.split(Regex("[^a-z]+")).any { it == "copy" || it == "copied" }
    }

    private fun isCopyToast(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length > 24) return false
        val lower = text.lowercase().trim()
        return copyToasts.any { lower == it || lower.startsWith(it) }
    }

    private fun selectedText(event: AccessibilityEvent): String? {
        runCatching {
            val node = event.source
            if (node != null) {
                val full = node.text?.toString()
                val start = node.textSelectionStart
                val end = node.textSelectionEnd
                if (!full.isNullOrEmpty() && start >= 0 && end > start && end <= full.length) {
                    val part = full.substring(start, end).trim()
                    if (part.isNotEmpty() && part.length <= 5000) return part
                }
            }
        }
        val eventText = event.text?.joinToString("")?.trim()
        return eventText?.takeIf { it.isNotEmpty() && it.length <= 5000 }
    }
}
