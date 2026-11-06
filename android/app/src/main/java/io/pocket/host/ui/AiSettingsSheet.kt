package io.pocket.host.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.pocket.host.core.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = remember { AiConfig.load(context) }

    var provider by remember { mutableStateOf(initial.provider) }
    var dsKey by remember { mutableStateOf(initial.deepseekKey) }
    var dsModel by remember { mutableStateOf(initial.deepseekModel.ifBlank { "deepseek-chat" }) }
    var cuBase by remember { mutableStateOf(initial.customBase) }
    var cuKey by remember { mutableStateOf(initial.customKey) }
    var cuModel by remember { mutableStateOf(initial.customModel) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    fun pasteKey(into: (String) -> Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, "剪贴板是空的", Toast.LENGTH_SHORT).show()
        } else {
            into(text)
        }
    }

    fun current(): AiConfig.Settings = AiConfig.Settings(
        provider = provider,
        deepseekKey = dsKey,
        deepseekModel = dsModel,
        customBase = cuBase,
        customKey = cuKey,
        customModel = cuModel
    )

    fun save() {
        AiConfig.save(context, current())
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        onSaved()
    }

    fun test() {
        testing = true
        testResult = "正在连接…"
        testOk = false
        val snapshot = current()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AiConfig.save(context, snapshot)
                    AiConfig.chat(
                        context,
                        listOf("user" to "只回复两个字：正常"),
                        system = "你是连通性测试，回答要极简。",
                        temperature = 0.0
                    )
                }
            }
            testing = false
            result.fold(
                onSuccess = { testOk = true; testResult = "连接正常：${it.take(40)}" },
                onFailure = { testOk = false; testResult = it.message ?: "连接失败" }
            )
        }
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
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "AI 模型",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Key 只保存在本机；口袋里的应用通过 pocket.ai.chat 统一调用，不用各自再配 Key。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderCard(
                    title = "DeepSeek",
                    subtitle = "只填 API Key",
                    selected = provider == AiConfig.PROVIDER_DEEPSEEK,
                    modifier = Modifier.weight(1f)
                ) { provider = AiConfig.PROVIDER_DEEPSEEK }
                ProviderCard(
                    title = "其他 / 中转站",
                    subtitle = "OpenAI 兼容格式",
                    selected = provider == AiConfig.PROVIDER_CUSTOM,
                    modifier = Modifier.weight(1f)
                ) { provider = AiConfig.PROVIDER_CUSTOM }
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (provider == AiConfig.PROVIDER_DEEPSEEK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dsKey,
                        onValueChange = { dsKey = it.trim() },
                        label = { Text("DeepSeek API Key") },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    TextButton(onClick = { pasteKey { dsKey = it } }) { Text("粘贴") }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = dsModel,
                    onValueChange = { dsModel = it.trim() },
                    label = { Text("模型") },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "地址固定为 https://api.deepseek.com，key 在 platform.deepseek.com 获取。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = cuBase,
                    onValueChange = { cuBase = it.trim() },
                    label = { Text("接口地址 Base URL") },
                    placeholder = { Text("https://your-proxy.com/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cuKey,
                        onValueChange = { cuKey = it.trim() },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    TextButton(onClick = { pasteKey { cuKey = it } }) { Text("粘贴") }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = cuModel,
                    onValueChange = { cuModel = it.trim() },
                    label = { Text("模型名") },
                    placeholder = { Text("gpt-4o-mini / claude-3-5-sonnet ...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "走标准 OpenAI 格式：POST {BaseURL}/chat/completions，带 Bearer Key。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (testResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = testResult,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (testOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { test() }, enabled = !testing) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                    }
                    Text("测试连接")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { save() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
