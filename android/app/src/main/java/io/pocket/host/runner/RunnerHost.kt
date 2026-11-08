package io.pocket.host.runner

import android.net.Uri
import org.json.JSONObject

sealed class DialogRequest {
    abstract val title: String

    data class Alert(
        override val title: String,
        val message: String,
        val button: String
    ) : DialogRequest()

    data class Confirm(
        override val title: String,
        val message: String,
        val okText: String,
        val cancelText: String
    ) : DialogRequest()

    data class Prompt(
        override val title: String,
        val message: String,
        val placeholder: String,
        val value: String,
        val multiline: Boolean,
        val okText: String,
        val cancelText: String
    ) : DialogRequest()

    data class Sheet(
        override val title: String,
        val items: List<String>,
        val cancelText: String
    ) : DialogRequest()
}

data class PickedFile(
    val name: String,
    val mime: String,
    val size: Long,
    val dataUrl: String
)

/** 只带 Uri 的选择结果：用于互传这种要直接流式发送、不该把文件读进内存的场景。 */
data class PickedRef(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long
)

data class SafeArea(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
    val keyboard: Int = 0,
    val viewportHeight: Int = 0,
    val dark: Boolean = false
)

interface RunnerHost {
    val appId: String
    val safeArea: SafeArea
    val isDarkTheme: Boolean

    fun showToast(text: String)
    fun showDialog(request: DialogRequest, onResult: (Any?) -> Unit)
    fun pickFile(accept: String, multiple: Boolean, onResult: (List<PickedFile>?) -> Unit)
    fun pickFiles(accept: String, multiple: Boolean, onResult: (List<PickedRef>?) -> Unit)
    fun requestNotificationPermission(onResult: (Boolean) -> Unit)
    fun applyStatusBar(style: String, color: String?)
    fun applyFullscreen(enabled: Boolean)
    fun applyKeepScreenOn(enabled: Boolean)
    fun openExternal(url: String)
    fun shareText(text: String, subject: String)
    fun emitEvent(name: String, payload: JSONObject)
    fun closeApp()
    fun restartApp()
}
