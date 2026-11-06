package io.pocket.host.runner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import io.pocket.host.R
import io.pocket.host.core.AiConfig
import io.pocket.host.core.BridgeException
import io.pocket.host.core.InstalledApp
import io.pocket.host.core.PackageRepository
import io.pocket.host.core.PocketStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * JS <-> 原生 的唯一通道。协议为 JSON，所有能力先查 manifest 权限。
 */
class PocketBridge(
    private val host: RunnerHost,
    private val webView: WebView,
    private val repo: PackageRepository,
    private val initialApp: InstalledApp,
    storageFile: File
) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val storage = PocketStorage(storageFile)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitDone = false
    private val ttsQueue = mutableListOf<TtsRequest>()

    private data class TtsRequest(
        val utteranceId: String,
        val text: String,
        val lang: String,
        val rate: Float,
        val pitch: Float
    )

    private val currentApp: InstalledApp
        get() = repo.get(initialApp.id) ?: initialApp

    @JavascriptInterface
    fun call(payload: String): String {
        return try {
            val request = JSONObject(payload)
            val method = request.optString("method")
            val params = request.optJSONObject("params") ?: JSONObject()
            val data = dispatchSync(method, params)
            envelope(true, data, null)
        } catch (e: BridgeException) {
            envelope(false, null, e)
        } catch (e: Throwable) {
            envelope(false, null, BridgeException("E_INTERNAL", e.message ?: "内部错误"))
        }
    }

    @JavascriptInterface
    fun callAsync(payload: String) {
        try {
            val request = JSONObject(payload)
            val id = request.optLong("id")
            val method = request.optString("method")
            val params = request.optJSONObject("params") ?: JSONObject()
            main.post { dispatchAsync(id, method, params) }
        } catch (e: Throwable) {
            // 解析失败时无从回调，忽略
        }
    }

    private fun dispatchSync(method: String, params: JSONObject): Any? {
        return when (method) {
            "app.info" -> appInfo()
            "app.close" -> { host.closeApp(); null }
            "app.restart" -> { host.restartApp(); null }
            "device.info" -> deviceInfo()
            "device.network" -> networkInfo()

            "storage.get" -> requirePermission("storage").let {
                val key = params.requireString("key")
                jsonOrNull(storage.get(key))
            }
            "storage.set" -> requirePermission("storage").let {
                val key = params.requireString("key")
                val value = if (params.isNull("value")) JSONObject.NULL else params.get("value")
                // 借 JSONArray 得到严格 JSON 编码，保证字符串/数字/对象都能原样往返
                val encoded = JSONArray().put(value).toString().removePrefix("[").removeSuffix("]")
                storage.set(key, encoded)
                true
            }
            "storage.remove" -> requirePermission("storage").let {
                storage.remove(params.requireString("key")); true
            }
            "storage.clear" -> requirePermission("storage").let { storage.clear(); true }
            "storage.keys" -> requirePermission("storage").let { JSONArray(storage.keys()) }
            "storage.size" -> requirePermission("storage").let { storage.bytes() }

            "ui.toast" -> {
                val text = params.optString("text")
                main.post { host.showToast(text) }
                null
            }
            "ui.haptic" -> {
                requirePermission("haptic")
                vibrate(params.optString("type", "light"))
                null
            }
            "ui.fullscreen" -> {
                val enabled = params.optBoolean("enabled", true)
                main.post { host.applyFullscreen(enabled) }
                null
            }
            "ui.keepScreenOn" -> {
                val enabled = params.optBoolean("enabled", true)
                main.post { host.applyKeepScreenOn(enabled) }
                null
            }
            "ui.statusBar" -> {
                val style = params.optString("style", "auto")
                val color = params.optString("color").ifEmpty { null }
                main.post { host.applyStatusBar(style, color) }
                null
            }

            "system.exit" -> { host.closeApp(); null }
            else -> throw BridgeException("E_NO_METHOD", "未知方法：$method")
        }
    }

    private fun dispatchAsync(id: Long, method: String, params: JSONObject) {
        try {
            when (method) {
                "ui.alert" -> {
                    host.showDialog(
                        DialogRequest.Alert(
                            params.optString("title", currentApp.name),
                            params.optString("message"),
                            params.optString("button", "好")
                        )
                    ) { resolve(id, true, null, null) }
                }

                "ui.confirm" -> host.showDialog(
                    DialogRequest.Confirm(
                        params.optString("title", currentApp.name),
                        params.optString("message"),
                        params.optString("okText", "确定"),
                        params.optString("cancelText", "取消")
                    )
                ) { result -> resolve(id, true, result == true, null) }

                "ui.prompt" -> host.showDialog(
                    DialogRequest.Prompt(
                        params.optString("title", currentApp.name),
                        params.optString("message"),
                        params.optString("placeholder"),
                        params.optString("value"),
                        params.optBoolean("multiline", false),
                        params.optString("okText", "确定"),
                        params.optString("cancelText", "取消")
                    )
                ) { result -> resolve(id, true, result, null) }

                "ui.actionSheet" -> {
                    val items = mutableListOf<String>()
                    params.optJSONArray("items")?.let {
                        for (i in 0 until it.length()) items.add(it.optString(i))
                    }
                    host.showDialog(
                        DialogRequest.Sheet(
                            params.optString("title", ""),
                            items,
                            params.optString("cancelText", "取消")
                        )
                    ) { result -> resolve(id, true, result, null) }
                }

                "clipboard.write" -> {
                    requirePermission("clipboard")
                    val text = params.optString("text")
                    val clipboard = hostContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("pocket", text))
                    resolve(id, true, null, null)
                }

                "clipboard.read" -> {
                    requirePermission("clipboard")
                    val clipboard = hostContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(hostContext()).toString() else ""
                    resolve(id, true, text, null)
                }

                "file.pick" -> {
                    requirePermission("file")
                    host.pickFile(
                        params.optString("accept", "*/*"),
                        params.optBoolean("multiple", false)
                    ) { files ->
                        if (files == null) {
                            resolve(id, false, null, BridgeException("E_CANCELLED", "已取消选择"))
                        } else {
                            val arr = JSONArray()
                            files.forEach { f ->
                                arr.put(
                                    JSONObject()
                                        .put("name", f.name)
                                        .put("mime", f.mime)
                                        .put("size", f.size)
                                        .put("dataUrl", f.dataUrl)
                                )
                            }
                            resolve(id, true, arr, null)
                        }
                    }
                }

                "share.text" -> {
                    requirePermission("share")
                    host.shareText(params.optString("text"), params.optString("subject", currentApp.name))
                    resolve(id, true, null, null)
                }

                "notify.show" -> {
                    requirePermission("notify")
                    host.requestNotificationPermission { granted ->
                        if (!granted) {
                            resolve(id, false, null, BridgeException("E_PERMISSION", "通知权限被拒绝"))
                        } else {
                            showNotification(params.optString("title", currentApp.name), params.optString("body"))
                            resolve(id, true, null, null)
                        }
                    }
                }

                "system.openExternal" -> {
                    requirePermission("external")
                    val url = params.optString("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        host.openExternal(url)
                        resolve(id, true, null, null)
                    } else {
                        resolve(id, false, null, BridgeException("E_INVALID_ARG", "仅支持 http/https 链接"))
                    }
                }

                "http.request" -> {
                    requirePermission("http")
                    handleHttp(id, params)
                }

                "ai.config" -> {
                    val active = AiConfig.active(hostContext())
                    resolve(
                        id, true,
                        JSONObject()
                            .put("available", active != null)
                            .put("provider", active?.provider ?: "")
                            .put("model", active?.model ?: ""),
                        null
                    )
                }

                "ai.chat" -> {
                    val system = params.optString("system", "")
                    val messages = mutableListOf<Pair<String, String>>()
                    params.optJSONArray("messages")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val content = item.optString("content")
                            if (content.isNotBlank()) {
                                messages.add(item.optString("role", "user") to content)
                            }
                        }
                    }
                    val prompt = params.optString("prompt", "")
                    if (messages.isEmpty() && prompt.isNotBlank()) messages.add("user" to prompt)
                    if (messages.isEmpty()) {
                        resolve(id, false, null, BridgeException("E_INVALID_ARG", "messages 不能为空"))
                    } else {
                        val temperature = params.optDouble("temperature", 0.7)
                        val maxTokens = params.optInt("maxTokens", 0)
                        io.execute {
                            runCatching {
                                AiConfig.chat(
                                    hostContext(), messages,
                                    system.ifBlank { null }, temperature, maxTokens
                                )
                            }.onSuccess { reply ->
                                resolve(
                                    id, true,
                                    JSONObject().put("text", reply.text).put("reasoning", reply.reasoning),
                                    null
                                )
                            }.onFailure { e ->
                                resolve(id, false, null, BridgeException("E_AI", e.message ?: "AI 调用失败"))
                            }
                        }
                    }
                }

                "tts.speak" -> {
                    val text = params.optString("text")
                    val utteranceId = params.optString("utteranceId").ifEmpty {
                        "pocket-tts-" + System.currentTimeMillis()
                    }
                    if (text.isNotEmpty()) {
                        speakTts(
                            TtsRequest(
                                utteranceId = utteranceId,
                                text = text,
                                lang = params.optString("lang", "en-US"),
                                rate = params.optDouble("rate", 1.0).toFloat(),
                                pitch = params.optDouble("pitch", 1.0).toFloat()
                            )
                        )
                    }
                    resolve(id, true, true, null)
                }

                "tts.stop" -> {
                    runCatching { tts?.stop() }
                    ttsQueue.clear()
                    resolve(id, true, true, null)
                }

                "tts.available" -> resolve(id, ttsReady, null, null)

                else -> resolve(id, false, null, BridgeException("E_NO_METHOD", "未知方法：$method"))
            }
        } catch (e: BridgeException) {
            resolve(id, false, null, e)
        } catch (e: Throwable) {
            resolve(id, false, null, BridgeException("E_INTERNAL", e.message ?: "内部错误"))
        }
    }

    private fun handleHttp(id: Long, params: JSONObject) {
        val urlText = params.optString("url")
        val uri = runCatching { Uri.parse(urlText) }.getOrNull()
        val targetHost = uri?.host?.lowercase()
        if (uri == null || uri.scheme != "https" || targetHost == null) {
            resolve(id, false, null, BridgeException("E_INVALID_ARG", "仅支持 https 地址"))
            return
        }
        val allowed = currentApp.networkWhitelist + currentApp.extraHosts
        if (targetHost !in allowed) {
            host.showDialog(
                DialogRequest.Confirm(
                    "网络访问请求",
                    "「${currentApp.name}」想访问 $targetHost，是否允许？",
                    "允许",
                    "拒绝"
                )
            ) { result ->
                if (result == true) {
                    repo.allowHost(currentApp.id, targetHost)
                    performHttp(id, params)
                } else {
                    resolve(id, false, null, BridgeException("E_NETWORK_DENIED", "已拒绝访问 $targetHost"))
                }
            }
            return
        }
        performHttp(id, params)
    }

    private fun performHttp(id: Long, params: JSONObject) {
        io.execute {
            val connection = try {
                val url = URL(params.optString("url"))
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = params.optString("method", "GET").uppercase(Locale.ROOT)
                    connectTimeout = params.optInt("timeout", 20_000).coerceIn(1_000, 60_000)
                    readTimeout = connectTimeout
                    instanceFollowRedirects = true
                    params.optJSONObject("headers")?.let { headers ->
                        headers.keys().forEach { key ->
                            setRequestProperty(key, headers.optString(key))
                        }
                    }
                    val body = params.optString("body")
                    if (body.isNotEmpty() && requestMethod != "GET" && requestMethod != "HEAD") {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
            } catch (e: Throwable) {
                resolve(id, false, null, BridgeException("E_NETWORK", "请求失败：${e.message}"))
                return@execute
            }

            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                val headers = JSONObject()
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && !values.isNullOrEmpty()) headers.put(key, values.joinToString(","))
                }
                val contentType = connection.contentType ?: ""
                val responseType = params.optString("responseType", "text")

                val bodyJson: Any = when {
                    responseType == "base64" || contentType.startsWith("image/") ->
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    contentType.contains("json") -> runCatching { JSONObject(String(bytes)) }.getOrElse { String(bytes) }
                    else -> String(bytes)
                }

                resolve(
                    id, true,
                    JSONObject()
                        .put("status", status)
                        .put("headers", headers)
                        .put("body", bodyJson)
                        .put("contentType", contentType)
                        .put("url", connection.url.toString()),
                    null
                )
            } catch (e: Throwable) {
                resolve(id, false, null, BridgeException("E_NETWORK", "请求失败：${e.message}"))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun appInfo(): JSONObject = JSONObject().apply {
        val app = currentApp
        put("id", app.id)
        put("name", app.name)
        put("version", app.version)
        put("description", app.description)
        put("accent", app.accent)
        put("permissions", JSONArray(app.permissions.toList()))
        put("source", app.source)
        put("installedAt", app.installedAt)
        put("launchCount", app.launchCount)
        put("origin", app.origin)
    }

    private fun deviceInfo(): JSONObject {
        val metrics = hostContext().resources.displayMetrics
        val area = host.safeArea
        return JSONObject().apply {
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("os", "Android ${Build.VERSION.RELEASE}")
            put("sdk", Build.VERSION.SDK_INT)
            put("width", metrics.widthPixels)
            put("height", metrics.heightPixels)
            put("density", metrics.density.toDouble())
            put("dpWidth", (metrics.widthPixels / metrics.density).roundToInt())
            put("dpHeight", (metrics.heightPixels / metrics.density).roundToInt())
            put("locale", Locale.getDefault().toLanguageTag())
            put("theme", if (host.isDarkTheme) "dark" else "light")
            put(
                "safeArea", JSONObject()
                    .put("top", area.top)
                    .put("bottom", area.bottom)
                    .put("left", area.left)
                    .put("right", area.right)
            )
            put("keyboard", area.keyboard)
            put("viewportHeight", area.viewportHeight)
            put("statusBar", host.safeArea.top)
            put("platform", "android")
            put("container", "pocket")
        }
    }

    private fun networkInfo(): JSONObject {
        val cm = hostContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val connected = capabilities != null
        val type = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
        val metered = capabilities?.let { cm.isActiveNetworkMetered } ?: false
        return JSONObject()
            .put("connected", connected)
            .put("type", type)
            .put("metered", metered)
            .put("online", connected)
    }

    /** 释放 TTS 引擎，避免退出后仍在后台占用语音。 */
    fun release() {
        ttsQueue.clear()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        ttsInitDone = false
    }

    private fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        val engine = TextToSpeech(hostContext()) { status ->
            main.post {
                ttsInitDone = true
                ttsReady = status == TextToSpeech.SUCCESS
                val pending = ttsQueue.toList()
                ttsQueue.clear()
                if (ttsReady) {
                    pending.forEach { speakTtsNow(it) }
                } else {
                    pending.forEach { emitTtsEvent(it.utteranceId, "error") }
                }
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                emitTtsEvent(utteranceId, "start")
            }

            override fun onDone(utteranceId: String?) {
                emitTtsEvent(utteranceId, "end")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                emitTtsEvent(utteranceId, "error")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                emitTtsEvent(utteranceId, "error")
            }
        })
        tts = engine
        return engine
    }

    private fun speakTts(request: TtsRequest) {
        ensureTts()
        if (!ttsInitDone) {
            ttsQueue.add(request)
            return
        }
        speakTtsNow(request)
    }

    private fun speakTtsNow(request: TtsRequest) {
        val engine = tts
        if (engine == null || !ttsReady) {
            emitTtsEvent(request.utteranceId, "error")
            return
        }
        try {
            val locale = Locale.forLanguageTag(request.lang.ifEmpty { "en-US" })
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            engine.setSpeechRate(request.rate.coerceIn(0.4f, 2.0f))
            engine.setPitch(request.pitch.coerceIn(0.5f, 2.0f))
            engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.utteranceId)
        } catch (e: Throwable) {
            emitTtsEvent(request.utteranceId, "error")
        }
    }

    private fun emitTtsEvent(utteranceId: String?, type: String) {
        if (utteranceId == null) return
        val script = "window.__pocketTtsEvent && window.__pocketTtsEvent(" +
            "${JSONObject.quote(utteranceId)}, ${JSONObject.quote(type)})"
        main.post {
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }

    private fun vibrate(type: String) {
        val vibrator = hostContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (type) {
                "medium" -> VibrationEffect.createOneShot(24, 70)
                "heavy" -> VibrationEffect.createOneShot(40, 90)
                "success" -> VibrationEffect.createWaveform(longArrayOf(0, 16, 80, 12), -1)
                "error" -> VibrationEffect.createWaveform(longArrayOf(0, 28, 60, 28), -1)
                else -> VibrationEffect.createOneShot(12, 60)
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20L)
        }
    }

    private fun showNotification(title: String, body: String) {
        val manager = hostContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "pocket_apps"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "小应用通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(hostContext(), channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun requirePermission(capability: String) {
        if (capability !in currentApp.permissions) {
            throw BridgeException(
                "E_PERMISSION",
                "应用未声明 \"$capability\" 权限，请在 manifest.json 的 permissions 中添加"
            )
        }
    }

    private fun resolve(id: Long, ok: Boolean, data: Any?, error: BridgeException?) {
        val payload = if (ok) {
            JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL).toString()
        } else {
            JSONObject()
                .put("ok", false)
                .put("code", error?.code ?: "E_INTERNAL")
                .put("message", error?.message ?: "未知错误")
                .toString()
        }
        val script = "window.__pocketResolve && window.__pocketResolve($id, ${JSONObject.quote(payload)})"
        main.post { webView.evaluateJavascript(script, null) }
    }

    private fun envelope(ok: Boolean, data: Any?, error: BridgeException?): String {
        return if (ok) {
            JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL).toString()
        } else {
            JSONObject()
                .put("ok", false)
                .put("code", error?.code ?: "E_INTERNAL")
                .put("message", error?.message ?: "未知错误")
                .toString()
        }
    }

    private fun jsonOrNull(raw: String?): Any {
        if (raw == null) return JSONObject.NULL
        return runCatching { org.json.JSONTokener(raw).nextValue() }.getOrElse { raw }
    }

    private fun hostContext(): Context = webView.context

    private fun JSONObject.requireString(key: String): String {
        val value = optString(key)
        if (value.isEmpty()) throw BridgeException("E_INVALID_ARG", "缺少参数 $key")
        return value
    }
}
