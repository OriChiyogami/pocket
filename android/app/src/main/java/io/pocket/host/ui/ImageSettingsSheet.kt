package io.pocket.host.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.pocket.host.core.ImageConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSettingsSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = remember { ImageConfig.load(context) }

    var base by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.key) }
    var model by remember { mutableStateOf(initial.model) }
    var showKey by remember { mutableStateOf(false) }

    fun pasteKey() {
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
            key = text
        }
    }

    fun save() {
        ImageConfig.save(
            context,
            ImageConfig.Settings(baseUrl = base, key = key, model = model)
        )
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        onSaved()
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
                text = "生图模型",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "OpenAI 兼容的中转站：没参考图走 /images/generations，带参考图走 /images/edits。Key 只保存在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = base,
                onValueChange = { base = it.trim() },
                label = { Text("接口地址 Base URL") },
                placeholder = { Text(ImageConfig.DEFAULT_BASE) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
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
                TextButton(onClick = { pasteKey() }) { Text("粘贴") }
            }
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it.trim() },
                label = { Text("模型名") },
                placeholder = { Text(ImageConfig.DEFAULT_MODEL) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "默认模型 ${ImageConfig.DEFAULT_MODEL}；精修可换成 gpt-image-2.5-sunburst。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))
            Row {
                TextButton(onClick = {
                    base = ImageConfig.DEFAULT_BASE
                    model = ImageConfig.DEFAULT_MODEL
                }) { Text("恢复默认地址与模型") }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { save() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) { Text("保存") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "手机里装的 APK 已经带了一份默认 Key，一般不用改；换中转站时才需要自己填。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}
