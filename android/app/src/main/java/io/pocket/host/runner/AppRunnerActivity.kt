package io.pocket.host.runner

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.pocket.host.PocketApp
import io.pocket.host.core.InstalledApp
import io.pocket.host.ui.theme.PocketTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

class AppRunnerActivity : ComponentActivity(), RunnerHost {

    private lateinit var app: InstalledApp
    private lateinit var webView: WebView
    private var bridge: PocketBridge? = null

    private var safeAreaState by mutableStateOf(SafeArea())
    private var progressState by mutableIntStateOf(0)
    private var loadingState by mutableStateOf(false)
    private var controlsVisibleState by mutableStateOf(false)
    private var dialogState by mutableStateOf<DialogState?>(null)

    private var foregroundStart = 0L
    private var pendingPick: ((List<PickedFile>?) -> Unit)? = null
    private var sdkCache: String? = null

    override val appId: String get() = app.id

    /**
     * 页面可用的安全区：默认模式（edgeToEdge = false）下 WebView 已被内缩到系统栏之内，
     * 所以页面内的安全区全部为 0，键盘也由窗口自身收缩处理。
     */
    override val safeArea: SafeArea
        get() = if (app.edgeToEdge) safeAreaState else SafeArea()
    override val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val pickSingle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handlePicked(listOfNotNull(uri))
    }

    private val pickMultiple = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePicked(uris)
    }

    private var pendingNotification: ((Boolean) -> Unit)? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingNotification?.invoke(granted)
        pendingNotification = null
    }

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            emit("networkchange", JSONObject().put("connected", true))
        }

        override fun onLost(network: android.net.Network) {
            emit("networkchange", JSONObject().put("connected", false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_APP_ID) ?: run { finish(); return }
        val loaded = PocketApp.of(this).repository.get(id) ?: run { finish(); return }
        app = loaded

        if (app.edgeToEdge) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
        } else {
            // 默认模式：像浏览器一样，内容区被内缩到系统栏之内，键盘通过 adjustResize 自然收缩视口
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = parseBackground()
            window.navigationBarColor = parseBackground()
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(parseBackground()))

        applyOrientation()
        buildWebView()

        PocketApp.of(this).repository.recordOpen(app.id)

        setContent {
            PocketTheme(darkTheme = isDarkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    RunnerScreen(
                        app = app,
                        progress = progressState,
                        loading = loadingState,
                        safeArea = safeAreaState,
                        controlsVisible = controlsVisibleState,
                        onToggleControls = { controlsVisibleState = !controlsVisibleState },
                        onBack = { performBack() },
                        onReload = {
                            progressState = 0
                            loadingState = true
                            webView.reload()
                        },
                        onClose = { finishRunner() },
                        edgeToEdge = app.edgeToEdge,
                        webView = webView
                    )
                }

                dialogState?.let { state ->
                    RunnerDialogHost(
                        request = state.request,
                        onResult = { result ->
                            dialogState = null
                            state.onResult(result)
                        }
                    )
                }
            }
        }

        setupInsets()
        applyStatusBar(app.statusBar, null)
        if (app.fullscreen) applyFullscreen(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                performBack()
            }
        })

        loadApp()

        runCatching {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onResume() {
        super.onResume()
        foregroundStart = SystemClock.elapsedRealtime()
        bridge?.let { emit("resume", JSONObject()) }
    }

    override fun onPause() {
        super.onPause()
        if (foregroundStart > 0) {
            val elapsed = SystemClock.elapsedRealtime() - foregroundStart
            PocketApp.of(this).repository.addForegroundTime(app.id, elapsed)
            foregroundStart = 0
        }
        bridge?.let { emit("pause", JSONObject()) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bridge?.let { emit("themechange", JSONObject().put("dark", isDarkTheme)) }
    }

    override fun onDestroy() {
        bridge?.let {
            emit("destroy", JSONObject())
            it.release()
        }
        runCatching {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        }
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 初始化

    private fun applyOrientation() {
        requestedOrientation = when (app.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                textZoom = 100
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = false
                useWideViewPort = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(parseBackground())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val repo = PocketApp.of(this).repository
        val wwwDir = repo.wwwDir(app.id)

        webView.webViewClient = PocketWebViewClient(
            context = this,
            app = app,
            wwwDir = wwwDir,
            sdkProvider = { sdkScript() },
            onPageLoaded = {
                maybeWipe()
            }
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressState = newProgress
                loadingState = newProgress < 100
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    android.util.Log.i(
                        "PocketWeb",
                        "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) = Unit
        }

        bridge = PocketBridge(
            host = this,
            webView = webView,
            repo = repo,
            initialApp = app,
            storageFile = java.io.File(repo.dataDir(app.id), "storage.json")
        )
        webView.addJavascriptInterface(bridge!!, "PocketNative")
    }

    private fun loadApp() {
        val segments = app.entry.split("/").filter { it.isNotEmpty() }
            .joinToString("/") { Uri.encode(it) }
        loadingState = true
        webView.loadUrl("https://${app.host}/$segments")
    }

    private fun sdkScript(): String {
        sdkCache?.let { return it }
        val text = assets.open("sdk/pocket-sdk.js").bufferedReader().use { it.readText() }
        sdkCache = text
        return text
    }

    private fun parseBackground(): Int = try {
        AndroidColor.parseColor(app.background)
    } catch (e: Exception) {
        AndroidColor.TRANSPARENT
    }

    // ------------------------------------------------------------------ 返回

    private fun performBack() {
        val js = "window.__pocketDispatchBack ? window.__pocketDispatchBack() : 'false'"
        webView.evaluateJavascript(js) { result ->
            val consumed = result?.trim()?.trim('"') == "true"
            if (!consumed) {
                finishRunner()
            } else {
                controlsVisibleState = false
            }
        }
    }

    private fun finishRunner() {
        if (foregroundStart > 0) {
            val elapsed = SystemClock.elapsedRealtime() - foregroundStart
            PocketApp.of(this).repository.addForegroundTime(app.id, elapsed)
            foregroundStart = 0
        }
        finish()
    }

    // ------------------------------------------------------------------ 窗口

    private fun setupInsets() {
        val density = resources.displayMetrics.density
        val root = window.decorView
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val previous = safeAreaState
            val next = SafeArea(
                top = (bars.top / density).roundToInt(),
                bottom = (bars.bottom / density).roundToInt(),
                left = (bars.left / density).roundToInt(),
                right = (bars.right / density).roundToInt(),
                keyboard = (ime.bottom / density).roundToInt(),
                viewportHeight = (resources.displayMetrics.heightPixels / density).roundToInt(),
                dark = isDarkTheme
            )
            safeAreaState = next
            if (previous.keyboard != next.keyboard) {
                emit("keyboard", JSONObject().put("height", next.keyboard))
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun controller(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(window, window.decorView)

    override fun applyStatusBar(style: String, color: String?) {
        val c = controller()
        c.isAppearanceLightStatusBars = when (style) {
            "light" -> true
            "dark" -> false
            else -> !isDarkTheme && app.edgeToEdge.not() && isLightBackground()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (style == "hidden") {
                c.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                c.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        window.statusBarColor = if (app.edgeToEdge) AndroidColor.TRANSPARENT else parseBackground()
    }

    private fun isLightBackground(): Boolean {
        val color = parseBackground()
        val r = AndroidColor.red(color) / 255.0
        val g = AndroidColor.green(color) / 255.0
        val b = AndroidColor.blue(color) / 255.0
        return (0.299 * r + 0.587 * g + 0.114 * b) > 0.6
    }

    override fun applyFullscreen(enabled: Boolean) {
        val c = controller()
        if (enabled) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ 宿主能力

    override fun showToast(text: String) {
        if (text.isBlank()) return
        val toast = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT)
        toast.show()
    }

    override fun showDialog(request: DialogRequest, onResult: (Any?) -> Unit) {
        dialogState = DialogState(request, onResult)
    }

    override fun pickFile(accept: String, multiple: Boolean, onResult: (List<PickedFile>?) -> Unit) {
        pendingPick = onResult
        val mime = if (accept.startsWith(".") || accept.isBlank()) "*/*" else accept
        runCatching {
            if (multiple) pickMultiple.launch(arrayOf(mime)) else pickSingle.launch(arrayOf(mime))
        }.onFailure {
            pendingPick = null
            onResult(null)
        }
    }

    private fun handlePicked(uris: List<Uri>) {
        val callback = pendingPick
        pendingPick = null
        if (callback == null) return
        if (uris.isEmpty()) {
            callback(null)
            return
        }
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> readPickedFile(uri) }
            }
            callback(files)
        }
    }

    private fun readPickedFile(uri: Uri): PickedFile? {
        return try {
            val resolver = contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = queryName(uri)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_PICK_BYTES) return null
            PickedFile(
                name = name,
                mime = mime,
                size = bytes.size.toLong(),
                dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name
    }

    override fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(true)
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            onResult(true)
            return
        }
        pendingNotification = onResult
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            if (it is ActivityNotFoundException) showToast("没有可以打开该链接的应用")
        }
    }

    override fun shareText(text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        startActivity(Intent.createChooser(intent, "分享到"))
    }

    override fun emitEvent(name: String, payload: JSONObject) {
        emit(name, payload)
    }

    private fun emit(name: String, payload: JSONObject) {
        runOnUiThread {
            val script = "window.__pocketEvent && window.__pocketEvent(" +
                JSONObject.quote(name) + ", " + JSONObject.quote(payload.toString()) + ")"
            webView.evaluateJavascript(script, null)
        }
    }

    override fun closeApp() {
        finishRunner()
    }

    override fun restartApp() {
        recreate()
    }

    // ------------------------------------------------------------------ 数据

    private fun maybeWipe() {
        val repo = PocketApp.of(this).repository
        if (!repo.consumeWipeFlag(app.id)) return
        webView.evaluateJavascript(WIPE_SCRIPT, null)
    }

    data class DialogState(val request: DialogRequest, val onResult: (Any?) -> Unit)

    companion object {
        const val EXTRA_APP_ID = "app_id"
        private const val MAX_PICK_BYTES = 8 * 1024 * 1024

        private const val WIPE_SCRIPT = """
            (function(){
              try{ localStorage.clear(); }catch(e){}
              try{ sessionStorage.clear(); }catch(e){}
              try{
                if (window.indexedDB && indexedDB.databases){
                  indexedDB.databases().then(function(dbs){
                    dbs.forEach(function(db){ if(db && db.name){ indexedDB.deleteDatabase(db.name); } });
                  });
                }
              }catch(e){}
              try{
                if (window.caches && caches.keys){
                  caches.keys().then(function(keys){ keys.forEach(function(k){ caches.delete(k); }); });
                }
              }catch(e){}
            })();
        """
    }
}
