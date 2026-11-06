package io.pocket.host.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.pocket.host.BuildConfig
import io.pocket.host.core.HostInfo
import io.pocket.host.core.LanClient
import io.pocket.host.core.LanServer
import io.pocket.host.core.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface UpdateState {
    data object Searching : UpdateState

    data class Result(
        val server: LanServer,
        val info: HostInfo?,
        val error: String? = null
    ) : UpdateState

    data class Downloading(val server: LanServer, val info: HostInfo) : UpdateState

    data class Failed(val message: String) : UpdateState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostUpdateSheet(
    onDismiss: () -> Unit,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var state by remember { mutableStateOf<UpdateState>(UpdateState.Searching) }
    var manualAddress by remember { mutableStateOf("") }
    var manualVisible by remember { mutableStateOf(false) }

    fun load(server: LanServer) {
        state = UpdateState.Searching
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { LanClient.fetchHost(server) }.getOrNull() }
            state = UpdateState.Result(server, info)
        }
    }

    fun search() {
        state = UpdateState.Searching
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // 优先复用上次导入成功过的地址，避免 UDP 广播被防火墙拦截时找不到电脑
                var hit: Pair<LanServer, HostInfo>? = null
                val saved = context.getSharedPreferences("pocket", Context.MODE_PRIVATE)
                    .getString("last_lan_server", null)
                val savedServer = saved?.let { LanClient.parseAddress(it) }
                if (savedServer != null) {
                    val info = runCatching { LanClient.fetchHost(savedServer) }.getOrNull()
                    if (info != null) hit = savedServer to info
                }
                val servers = runCatching { LanClient.discover() }.getOrDefault(emptyList())
                // 多网卡时会发现多台，挑第一台真正放了安装包的
                if (hit == null) {
                    for (server in servers) {
                        val info = runCatching { LanClient.fetchHost(server) }.getOrNull() ?: continue
                        hit = server to info
                        break
                    }
                }
                Triple(servers, hit, savedServer ?: servers.firstOrNull())
            }
            val (servers, hit, first) = result
            when {
                hit != null -> state = UpdateState.Result(hit.first, hit.second)
                first != null -> state = UpdateState.Result(first, null)
                else -> {
                    state = UpdateState.Failed("没有发现电脑端服务")
                    manualVisible = true
                }
            }
            if (servers.isEmpty()) manualVisible = true
        }
    }

    fun download(server: LanServer, info: HostInfo) {
        state = UpdateState.Downloading(server, info)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(context.cacheDir, "host-update.apk")
                    LanClient.downloadHost(server, dest)
                    dest
                }
            }
            result.fold(
                onSuccess = { file ->
                    state = UpdateState.Result(server, info)
                    installApk(context, file)
                    onInstalled()
                },
                onFailure = { error ->
                    state = UpdateState.Failed("下载失败：${error.message ?: "未知错误"}")
                }
            )
        }
    }

    LaunchedEffect(Unit) { search() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "检查更新",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "从电脑端下载最新口袋安装包；当前 v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(18.dp))

            when (val current = state) {
                is UpdateState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        "正在搜索电脑并读取版本…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is UpdateState.Result -> {
                    Text(
                        text = current.server.name + " · " + current.server.display,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val info = current.info
                    if (info == null) {
                        HintCard("电脑端还没有放置 pocket-host.apk，先在电脑上把新版本放进 dist 目录。")
                    } else {
                        val newer = info.versionCode > BuildConfig.VERSION_CODE
                        HintCard(
                            text = if (newer) "发现新版本 v${info.version}（${formatBytes(info.size)}）"
                            else "已是最新版本 v${info.version}（${formatBytes(info.size)}）",
                            highlight = newer
                        )
                        if (info.notes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                info.notes,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (newer) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { download(current.server, info) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("下载并安装", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { search() }) { Text("重新检查") }
                        TextButton(onClick = { manualVisible = !manualVisible }) { Text("手动输入地址") }
                    }
                }

                is UpdateState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            "正在下载 v${current.info.version}…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UpdateState.Failed -> {
                    HintCard(current.message, error = true)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { search() }) { Text("重新搜索") }
                        TextButton(onClick = { manualVisible = !manualVisible }) { Text("手动输入地址") }
                    }
                }
            }

            if (manualVisible && state !is UpdateState.Downloading) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        placeholder = { Text("192.168.137.1:8570") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Button(
                        onClick = {
                            val server = LanClient.parseAddress(manualAddress)
                            if (server == null) {
                                Toast.makeText(context, "格式应为 192.168.137.1:8570", Toast.LENGTH_SHORT).show()
                            } else {
                                load(server)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun HintCard(text: String, highlight: Boolean = false, error: Boolean = false) {
    val tone = when {
        error -> MaterialTheme.colorScheme.error
        highlight -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tone.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = tone
        )
    }
}

/** 触发系统安装器；未授予「安装未知应用」时先跳到授权页。 */
private fun installApk(context: Context, file: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        Toast.makeText(context, "请先允许口袋安装应用，再回来点一次下载并安装", Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    }

    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    Toast.makeText(context, "已交给系统安装器，按提示完成安装", Toast.LENGTH_SHORT).show()
}
