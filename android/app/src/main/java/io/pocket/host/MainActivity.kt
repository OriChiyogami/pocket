package io.pocket.host

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.pocket.host.core.InstalledApp
import io.pocket.host.core.PackageInstaller
import io.pocket.host.core.PackageRepository
import io.pocket.host.runner.AppRunnerActivity
import io.pocket.host.core.AiConfig
import io.pocket.host.core.AutoUpdate
import io.pocket.host.core.ImageConfig
import io.pocket.host.ui.AiChatScreen
import io.pocket.host.ui.AiSettingsSheet
import io.pocket.host.ui.AppDetailSheet
import io.pocket.host.ui.FolderPickerDialog
import io.pocket.host.ui.FolderScreen
import io.pocket.host.ui.HomeScreen
import io.pocket.host.ui.HostUpdateSheet
import io.pocket.host.ui.ImageGenScreen
import io.pocket.host.ui.ImageSettingsSheet
import io.pocket.host.ui.ImportSheet
import io.pocket.host.ui.LanImportSheet
import io.pocket.host.ui.PasteHtmlDialog
import io.pocket.host.ui.RenameFolderDialog
import io.pocket.host.ui.SettingsScreen
import io.pocket.host.ui.theme.PocketTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var repo: PackageRepository
    private lateinit var installer: PackageInstaller

    private var settingsOpen by mutableStateOf(false)
    private var importSheetOpen by mutableStateOf(false)
    private var lanSheetOpen by mutableStateOf(false)
    private var detail by mutableStateOf<InstalledApp?>(null)
    private var updateSheetOpen by mutableStateOf(false)
    private var folderScreen by mutableStateOf<String?>(null)
    private var folderRenameTarget by mutableStateOf<String?>(null)
    private var folderPickerApp by mutableStateOf<InstalledApp?>(null)
    private var pasteOpen by mutableStateOf(false)
    private var pastePrefill by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var confirmState by mutableStateOf<ConfirmState?>(null)
    private var aiSettingsOpen by mutableStateOf(false)
    private var aiChatOpen by mutableStateOf(false)
    private var aiSummary by mutableStateOf("")
    private var imageGenOpen by mutableStateOf(false)
    private var imageSettingsOpen by mutableStateOf(false)
    private var imageSummary by mutableStateOf("")
    private var autoSyncOn by mutableStateOf(true)

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 顶部状态栏融入浅色页面：透明背景 + 深色图标，避免出现一条黑色横条
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1401)
        }

        val app = PocketApp.of(this)
        repo = app.repository
        installer = app.installer
        aiSummary = AiConfig.load(this).summary()
        imageSummary = ImageConfig.load(this).summary()
        autoSyncOn = AutoUpdate.enabled(this)

        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                installer.ensureBuiltins("hello.pkt", "motion.pkt", "tool.pkt")
                installer.ensureBuiltinVersion("transfer.pkt", "com.pocket.transfer", "1.2.0")
                installer.ensureBuiltinVersion("files.pkt", "com.pocket.files", "1.3.0")
                installer.ensureBuiltinVersion("clipboard.pkt", "com.pocket.clipboard", "1.3.0")
                // 电脑端上版本更高的小应用，启动时静默更新（数据保留）
                runCatching { AutoUpdate.syncApps(this@MainActivity) }.getOrDefault(emptyList())
            }
            if (updated.isNotEmpty()) {
                toast("已自动更新：" + updated.joinToString("、"))
            }
        }

        setContent {
            PocketTheme {
                val apps by repo.apps.collectAsState()
                val folders by repo.folders.collectAsState()
                val detailApp = detail?.let { current -> apps.firstOrNull { it.id == current.id } }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AnimatedContent(
                        targetState = folderScreen,
                        transitionSpec = {
                            val forward = targetState != null
                            (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) {
                                if (forward) it / 3 else -it / 3
                            } + fadeIn(tween(260, easing = FastOutSlowInEasing))) togetherWith
                                (slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) {
                                    if (forward) -it / 3 else it / 3
                                } + fadeOut(tween(180)))
                        },
                        label = "folder-screen",
                        modifier = Modifier.fillMaxSize()
                    ) { folder ->
                        if (folder != null) {
                            FolderScreen(
                                name = folder,
                                apps = apps.filter { it.folder == folder },
                                onBack = { folderScreen = null },
                                onOpen = { openApp(it) },
                                onLongPress = { detail = it },
                                onRename = { folderRenameTarget = folder },
                                onDelete = {
                                    repo.deleteFolder(folder)
                                    toast("已解散「$folder」，应用已回到桌面")
                                    folderScreen = null
                                },
                                modifier = Modifier.systemBarsPadding()
                            )
                        } else {
                            AnimatedContent(
                                targetState = settingsOpen,
                                transitionSpec = {
                                    if (targetState) {
                                        (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(260, easing = FastOutSlowInEasing))) togetherWith
                                            (slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(180)))
                                    } else {
                                        (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(260, easing = FastOutSlowInEasing))) togetherWith
                                            (slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180)))
                                    }
                                },
                                label = "home-settings",
                                modifier = Modifier.fillMaxSize()
                            ) { showSettings ->
                                if (showSettings) {
                                SettingsScreen(
                                    apps = apps,
                                    onBack = { settingsOpen = false },
                                    showBack = false,
                                    onRestoreExamples = { restoreExamples() },
                                    onCheckUpdate = { updateSheetOpen = true },
                                    onOpenAi = { aiSettingsOpen = true },
                                    aiSummary = aiSummary,
                                    onOpenImage = { imageSettingsOpen = true },
                                    imageSummary = imageSummary,
                                    autoSync = autoSyncOn,
                                    onToggleAutoSync = { value ->
                                        autoSyncOn = value
                                        AutoUpdate.setEnabled(this@MainActivity, value)
                                    },
                                    modifier = Modifier
                                        .systemBarsPadding()
                                        .padding(bottom = 92.dp)
                                )
                            } else {
                                HomeScreen(
                                    apps = apps,
                                    folders = folders,
                                    onOpen = { app, bounds -> openApp(app, bounds) },
                                    onLongPress = { detail = it },
                                    onOpenFolder = { folderScreen = it },
                                    onAdd = { importSheetOpen = true },
                                    onSettings = { settingsOpen = true },
                                    onAi = { aiChatOpen = true },
                                    onDraw = { imageGenOpen = true },
                                    onTransfer = {
                                        apps.firstOrNull { it.id == "com.pocket.transfer" }?.let { openApp(it) }
                                    },
                                    onClipboard = {
                                        apps.firstOrNull { it.id == "com.pocket.clipboard" }?.let { openApp(it) }
                                    },
                                    onFiles = {
                                        apps.firstOrNull { it.id == "com.pocket.files" }?.let { openApp(it) }
                                    },
                                    modifier = Modifier.systemBarsPadding()
                                )
                            }
                        }
                    }
                    }

                    if (folderScreen == null && !aiChatOpen && !imageGenOpen &&
                        !aiSettingsOpen && !imageSettingsOpen
                    ) {
                        MainBottomBar(
                            selected = if (settingsOpen) 1 else 0,
                            onHome = { settingsOpen = false },
                            onSettings = { settingsOpen = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 26.dp, vertical = 12.dp)
                                .navigationBarsPadding()
                        )
                    }

                    if (busy) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(22.dp)
                                    )
                                    .padding(26.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }
                }

                if (importSheetOpen) {
                    ImportSheet(
                        onDismiss = { importSheetOpen = false },
                        onPickFile = {
                            importSheetOpen = false
                            importPicker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/vnd.pocket.app",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        onPasteHtml = {
                            importSheetOpen = false
                            pastePrefill = ""
                            pasteOpen = true
                        },
                        onLanImport = {
                            importSheetOpen = false
                            lanSheetOpen = true
                        },
                        onRestoreExamples = {
                            importSheetOpen = false
                            restoreExamples()
                        }
                    )
                }

                if (updateSheetOpen) {
                    HostUpdateSheet(
                        onDismiss = { updateSheetOpen = false },
                        onInstalled = {
                            updateSheetOpen = false
                            toast("安装包已下载，按系统提示完成安装")
                        }
                    )
                }

                if (lanSheetOpen) {
                    LanImportSheet(
                        onDismiss = { lanSheetOpen = false }
                    )
                }

                if (aiChatOpen) {
                    AiChatScreen(
                        onBack = { aiChatOpen = false },
                        onOpenSettings = { aiSettingsOpen = true },
                        aiSummary = aiSummary
                    )
                }

                if (aiSettingsOpen) {
                    AiSettingsSheet(
                        onDismiss = { aiSettingsOpen = false },
                        onSaved = { aiSummary = AiConfig.load(this@MainActivity).summary() }
                    )
                }

                if (imageGenOpen) {
                    ImageGenScreen(
                        onBack = { imageGenOpen = false },
                        onOpenSettings = { imageSettingsOpen = true },
                        imageSummary = imageSummary
                    )
                }

                if (imageSettingsOpen) {
                    ImageSettingsSheet(
                        onDismiss = { imageSettingsOpen = false },
                        onSaved = { imageSummary = ImageConfig.load(this@MainActivity).summary() }
                    )
                }

                if (detailApp != null) {
                    AppDetailSheet(
                        app = detailApp,
                        onDismiss = { detail = null },
                        onOpen = { openApp(detailApp) },
                        onPickFolder = {
                            detail = null
                            folderPickerApp = detailApp
                        },
                        onClearData = {
                            detail = null
                            confirmState = ConfirmState(
                                title = "清除应用数据",
                                message = "「${detailApp.name}」的本地存储、缓存与网页数据会被清空，应用本身保留。",
                                confirmText = "清除"
                            ) {
                                repo.clearAppData(detailApp.id)
                                toast("已清除「${detailApp.name}」的数据")
                            }
                        },
                        onDelete = {
                            detail = null
                            confirmState = ConfirmState(
                                title = "删除应用",
                                message = "「${detailApp.name}」将被卸载，数据无法恢复。",
                                confirmText = "删除"
                            ) {
                                repo.remove(detailApp.id)
                                toast("已删除「${detailApp.name}」")
                            }
                        }
                    )
                }

                if (pasteOpen) {
                    PasteHtmlDialog(
                        initialHtml = pastePrefill,
                        onDismiss = { pasteOpen = false },
                        onSave = { name, html -> saveHtml(name, html) }
                    )
                }

                folderPickerApp?.let { target ->
                    FolderPickerDialog(
                        folders = folders,
                        current = target.folder,
                        onDismiss = { folderPickerApp = null },
                        onPick = { folder ->
                            repo.moveToFolder(target.id, folder)
                            toast(
                                if (folder == null) "已移出文件夹" else "已归入「$folder」"
                            )
                            folderPickerApp = null
                        }
                    )
                }

                folderRenameTarget?.let { target ->
                    RenameFolderDialog(
                        initial = target,
                        onDismiss = { folderRenameTarget = null },
                        onConfirm = { next ->
                            repo.renameFolder(target, next)
                            if (folderScreen == target) folderScreen = next
                            folderRenameTarget = null
                        }
                    )
                }

                confirmState?.let { state ->
                    ConfirmDialog(
                        state = state,
                        onDismiss = { confirmState = null }
                    )
                }

                BackHandler(enabled = settingsOpen || folderScreen != null || aiChatOpen || imageGenOpen) {
                    if (imageGenOpen) {
                        imageGenOpen = false
                    } else if (aiChatOpen) {
                        aiChatOpen = false
                    } else if (folderScreen != null) {
                        folderScreen = null
                    } else {
                        settingsOpen = false
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ 导入

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val type = runCatching { contentResolver.getType(uri) }.getOrNull()
                if (type != null && (type.startsWith("text/") || type.contains("html"))) {
                    lifecycleScope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            }.getOrNull()
                        }
                        if (text != null) {
                            pastePrefill = text
                            importSheetOpen = false
                            pasteOpen = true
                        } else {
                            importUri(uri)
                        }
                    }
                } else {
                    importUri(uri)
                }
            }

            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    pastePrefill = text
                    pasteOpen = true
                }
            }
        }
    }

    private fun importUri(uri: Uri) {
        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { installer.installFromUri(uri, "file") }
            }
            busy = false
            result.onSuccess { installed ->
                toast("已安装「${installed.name}」")
                detail = installed
            }.onFailure { error ->
                toast("导入失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun saveHtml(name: String, html: String) {
        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { installer.installFromHtml(html, name) }
            }
            busy = false
            pasteOpen = false
            result.onSuccess { installed ->
                toast("已保存「${installed.name}」")
                openApp(installed)
            }.onFailure { error ->
                toast("保存失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun restoreExamples() {
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                listOf("hello.pkt", "motion.pkt", "tool.pkt", "transfer.pkt", "files.pkt", "clipboard.pkt")
                    .forEach { installer.installBuiltin(it) }
            }
            busy = false
            toast("示例应用已就绪")
        }
    }

    // ------------------------------------------------------------------ 运行

    private fun openApp(app: InstalledApp, bounds: IntRect? = null) {
        detail = null
        val intent = Intent(this, AppRunnerActivity::class.java)
            .putExtra(AppRunnerActivity.EXTRA_APP_ID, app.id)
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            try {
                val options = ActivityOptions.makeScaleUpAnimation(
                    window.decorView,
                    bounds.left,
                    bounds.top,
                    bounds.width,
                    bounds.height
                )
                startActivity(intent, options.toBundle())
                return
            } catch (e: Throwable) {
                // 某些 ROM 不支持 scale-up 动画时退回普通启动
            }
        }
        startActivity(intent)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    data class ConfirmState(
        val title: String,
        val message: String,
        val confirmText: String,
        val onConfirm: () -> Unit
    )
}

@Composable
private fun MainBottomBar(
    selected: Int,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .width(228.dp)
            .height(60.dp)
            .shadow(18.dp, RoundedCornerShape(23.dp), clip = false)
            .clip(RoundedCornerShape(23.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE4E4DF), RoundedCornerShape(23.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomItem("首页", Icons.Filled.Home, selected == 0, onHome, Modifier.weight(1f))
        BottomItem("设置", Icons.Filled.Settings, selected == 1, onSettings, Modifier.weight(1f))
    }
}

@Composable
private fun BottomItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint by animateColorAsState(
        targetValue = if (selected) Color(0xFF141414) else Color(0xFF9A9A96),
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "bottom-tint"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "bottom-scale"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.width(18.dp).height(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
    }
}

@Composable
private fun ConfirmDialog(state: MainActivity.ConfirmState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(state.title) },
        text = { Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                state.onConfirm()
            }) {
                Text(state.confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
