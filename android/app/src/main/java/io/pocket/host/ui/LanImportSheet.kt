package io.pocket.host.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.pocket.host.PocketApp
import io.pocket.host.core.LanClient
import io.pocket.host.core.LanServer
import io.pocket.host.core.RemoteApp
import io.pocket.host.core.formatBytes
import io.pocket.host.ui.theme.parseAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface LanState {
    data object Searching : LanState
    data class ServerList(val servers: List<LanServer>, val error: String? = null) : LanState
    data class Loading(val server: LanServer) : LanState
    data class AppList(
        val server: LanServer,
        val apps: List<RemoteApp>,
        val installingId: String? = null
    ) : LanState
    data class Failed(val server: LanServer?, val message: String) : LanState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanImportSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val prefs = remember {
        context.getSharedPreferences("pocket", android.content.Context.MODE_PRIVATE)
    }
    var state by remember { mutableStateOf<LanState>(LanState.Searching) }
    // 记住上次成功连上的电脑，下次优先直连，免去重新搜索和手填地址
    var manualAddress by remember { mutableStateOf(prefs.getString("last_lan_server", "").orEmpty()) }
    var manualVisible by remember { mutableStateOf(false) }

    fun search() {
        state = LanState.Searching
        scope.launch {
            val servers = withContext(Dispatchers.IO) {
                runCatching { LanClient.discover() }.getOrDefault(emptyList())
            }
            state = LanState.ServerList(servers)
            if (servers.isEmpty()) manualVisible = true
        }
    }

    fun connect(server: LanServer) {
        state = LanState.Loading(server)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LanClient.fetchApps(server) } }
            state = result.fold(
                onSuccess = {
                    prefs.edit().putString("last_lan_server", "${server.host}:${server.port}").apply()
                    LanState.AppList(server, it)
                },
                onFailure = { LanState.Failed(server, it.message ?: "无法连接到电脑") }
            )
        }
    }

    fun refresh(server: LanServer) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LanClient.fetchApps(server) } }
            result.onSuccess { apps ->
                val current = state as? LanState.AppList
                if (current != null && current.server.host == server.host) {
                    state = current.copy(apps = apps, installingId = null)
                }
            }
            result.onFailure {
                (state as? LanState.AppList)?.let { current -> state = current.copy(installingId = null) }
            }
        }
    }

    fun install(server: LanServer, app: RemoteApp) {
        val current = state as? LanState.AppList ?: return
        if (current.installingId != null) return
        val wasInstalled = PocketApp.of(context).repository.apps.value.any { it.id == app.id }
        state = current.copy(installingId = app.id)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.filesDir, "tmp/lan-${System.currentTimeMillis()}.pkt")
                    tmp.parentFile?.mkdirs()
                    LanClient.downloadTo(server, app, tmp)
                    val installed = PocketApp.of(context).installer.installFromZipFile(tmp, "lan")
                    tmp.delete()
                    installed
                }
            }
            result.fold(
                onSuccess = { installed ->
                    // 装完留在本页继续挑下一个，不跳走
                    Toast.makeText(
                        context,
                        "已${if (wasInstalled) "更新" else "安装"}「${installed.name}」v${installed.version}",
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh(server)
                },
                onFailure = { error ->
                    state = (state as? LanState.AppList)?.copy(installingId = null)
                        ?: LanState.Failed(server, error.message ?: "安装失败")
                    Toast.makeText(context, "安装失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val saved = LanClient.parseAddress(manualAddress)
        if (saved != null) connect(saved) else search()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 22.dp)
                .padding(bottom = 10.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "从电脑导入",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "手机和电脑连同一个 Wi-Fi（电脑热点即可），电脑上运行 pocket serve",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            when (val current = state) {
                is LanState.Searching -> SearchingBlock()

                is LanState.ServerList -> ServerListBlock(
                    servers = current.servers,
                    manualVisible = manualVisible,
                    manualAddress = manualAddress,
                    onManualChange = { manualAddress = it },
                    onToggleManual = { manualVisible = !manualVisible },
                    onConnect = { connect(it) },
                    onConnectManual = {
                        val server = LanClient.parseAddress(manualAddress)
                        if (server == null) {
                            Toast.makeText(context, "地址格式应为 192.168.137.1:8570", Toast.LENGTH_SHORT).show()
                        } else {
                            connect(server)
                        }
                    },
                    onRescan = { manualVisible = false; search() }
                )

                is LanState.Loading -> LoadingBlock(current.server.name)

                is LanState.AppList -> AppListBlock(
                    server = current.server,
                    apps = current.apps,
                    installingId = current.installingId,
                    onInstall = { install(current.server, it) },
                    onBack = { state = LanState.ServerList(emptyList()); search() }
                )

                is LanState.Failed -> FailedBlock(
                    message = current.message,
                    onRetry = {
                        val server = current.server
                        if (server != null) connect(server) else { manualVisible = true; search() }
                    },
                    onOther = { manualVisible = true; search() }
                )
            }
        }
    }
}

@Composable
private fun SearchingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "正在搜索局域网内的电脑…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ServerListBlock(
    servers: List<LanServer>,
    manualVisible: Boolean,
    manualAddress: String,
    onManualChange: (String) -> Unit,
    onToggleManual: () -> Unit,
    onConnect: (LanServer) -> Unit,
    onConnectManual: () -> Unit,
    onRescan: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (servers.isNotEmpty()) {
            Text(
                text = "发现 ${servers.size} 台电脑",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            servers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PcBadge()
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = server.display,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onConnect(server) }) {
                        Text("连接", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            Text(
                text = "没有自动发现电脑",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "确认电脑上已运行 pocket serve，且 Windows 防火墙允许 node.exe 通过专用网络。也可以在下面手动填写地址。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        TextButton(onClick = onRescan, modifier = Modifier.padding(top = 2.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text("重新搜索")
        }

        if (manualVisible) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = onManualChange,
                    placeholder = { Text("192.168.137.1:8570") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Button(
                    onClick = onConnectManual,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("连接")
                }
            }
        } else {
            TextButton(onClick = onToggleManual) {
                Text("手动输入地址")
            }
        }
    }
}

@Composable
private fun LoadingBlock(serverName: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "正在读取 $serverName 的应用列表…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppListBlock(
    server: LanServer,
    apps: List<RemoteApp>,
    installingId: String?,
    onInstall: (RemoteApp) -> Unit,
    onBack: () -> Unit
) {
    val installed = PocketApp.of(LocalContext.current).repository.apps.value
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) { Text("换一台") }
        }

        if (apps.isEmpty()) {
            Text(
                text = "电脑上还没有 .pkt 包",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 30.dp)
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(apps, key = { it.id.ifEmpty { it.file } }) { app ->
                RemoteAppRow(
                    server = server,
                    app = app,
                    existingVersion = installed.firstOrNull {
                        it.id == app.id ||
                            it.name.replace(" ", "") == app.name.replace(" ", "")
                    }?.version,
                    installing = installingId == app.id,
                    disabled = installingId != null,
                    onInstall = { onInstall(app) }
                )
            }
        }
    }
}

@Composable
private fun RemoteAppRow(
    server: LanServer,
    app: RemoteApp,
    existingVersion: String?,
    installing: Boolean,
    disabled: Boolean,
    onInstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteAppIcon(server = server, app = app, size = 46.dp)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (existingVersion != null && existingVersion != app.version)
                    "已装 v$existingVersion → 新 v${app.version} · ${formatBytes(app.size)}"
                else
                    "v${app.version} · ${formatBytes(app.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (app.description.isNotEmpty()) {
                Text(
                    text = app.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Button(
            onClick = onInstall,
            enabled = !disabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = parseAccent(app.accent))
        ) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Text(
                    when {
                        existingVersion == null -> "安装"
                        existingVersion == app.version -> "重装"
                        else -> "更新"
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteAppIcon(server: LanServer, app: RemoteApp, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, app.id, server.host) {
        value = if (!app.hasIcon) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val bytes = LanClient.fetchIcon(server, app.id)
                if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }

    val accent = parseAccent(app.accent)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(accent),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = app.name,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Text(
                text = app.name.take(1),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PcBadge() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "PC",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun FailedBlock(message: String, onRetry: () -> Unit, onOther: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp)) {
        Text(
            text = "连接失败",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) {
                Text("重试")
            }
            Spacer(modifier = Modifier.size(10.dp))
            TextButton(onClick = onOther) {
                Text("换一台 / 手动输入")
            }
        }
    }
}
