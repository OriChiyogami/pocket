package io.pocket.host

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.pocket.host.core.InstalledApp
import io.pocket.host.core.PackageInstaller
import io.pocket.host.core.PackageRepository
import io.pocket.host.runner.AppRunnerActivity
import io.pocket.host.core.AiConfig
import io.pocket.host.ui.AiChatScreen
import io.pocket.host.ui.AiSettingsSheet
import io.pocket.host.ui.AppDetailSheet
import io.pocket.host.ui.FolderPickerDialog
import io.pocket.host.ui.FolderScreen
import io.pocket.host.ui.HomeScreen
import io.pocket.host.ui.HostUpdateSheet
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

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = PocketApp.of(this)
        repo = app.repository
        installer = app.installer
        aiSummary = AiConfig.load(this).summary()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                installer.ensureBuiltins("hello.pkt", "motion.pkt", "tool.pkt")
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
                    if (settingsOpen) {
                        SettingsScreen(
                            apps = apps,
                            onBack = { settingsOpen = false },
                            onRestoreExamples = { restoreExamples() },
                            onCheckUpdate = { updateSheetOpen = true },
                            onOpenAi = { aiSettingsOpen = true },
                            aiSummary = aiSummary,
                            modifier = Modifier.systemBarsPadding()
                        )
                    } else if (folderScreen != null) {
                        FolderScreen(
                            name = folderScreen ?: "",
                            apps = apps.filter { it.folder == folderScreen },
                            onBack = { folderScreen = null },
                            onOpen = { openApp(it) },
                            onLongPress = { detail = it },
                            onRename = { folderRenameTarget = folderScreen },
                            onDelete = {
                                folderScreen?.let { name ->
                                    repo.deleteFolder(name)
                                    toast("已解散「$name」，应用已回到桌面")
                                    folderScreen = null
                                }
                            },
                            modifier = Modifier.systemBarsPadding()
                        )
                    } else {
                        HomeScreen(
                            apps = apps,
                            folders = folders,
                            onOpen = { openApp(it) },
                            onLongPress = { detail = it },
                            onOpenFolder = { folderScreen = it },
                            onAdd = { importSheetOpen = true },
                            onSettings = { settingsOpen = true },
                            onAi = { aiChatOpen = true },
                            modifier = Modifier.systemBarsPadding()
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

                BackHandler(enabled = settingsOpen || folderScreen != null || aiChatOpen) {
                    if (aiChatOpen) {
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
                listOf("hello.pkt", "motion.pkt", "tool.pkt").forEach { installer.installBuiltin(it) }
            }
            busy = false
            toast("示例应用已就绪")
        }
    }

    // ------------------------------------------------------------------ 运行

    private fun openApp(app: InstalledApp) {
        detail = null
        startActivity(
            Intent(this, AppRunnerActivity::class.java)
                .putExtra(AppRunnerActivity.EXTRA_APP_ID, app.id)
        )
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
