package io.pocket.host.runner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RunnerDialogHost(request: DialogRequest, onResult: (Any?) -> Unit) {
    when (request) {
        is DialogRequest.Alert -> AlertDialog(
            onDismissRequest = { onResult(null) },
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(request.title, fontWeight = FontWeight.Bold) },
            text = { Text(request.message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { onResult(null) }) { Text(request.button) }
            }
        )

        is DialogRequest.Confirm -> AlertDialog(
            onDismissRequest = { onResult(false) },
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(request.title, fontWeight = FontWeight.Bold) },
            text = { Text(request.message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { onResult(true) }) { Text(request.okText) }
            },
            dismissButton = {
                TextButton(onClick = { onResult(false) }) {
                    Text(request.cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )

        is DialogRequest.Prompt -> PromptDialog(request, onResult)

        is DialogRequest.Sheet -> SheetDialog(request, onResult)
    }
}

@Composable
private fun PromptDialog(request: DialogRequest.Prompt, onResult: (Any?) -> Unit) {
    var value by remember { mutableStateOf(request.value) }

    AlertDialog(
        onDismissRequest = { onResult(null) },
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(request.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request.message.isNotEmpty()) {
                    Text(request.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(request.placeholder) },
                    singleLine = !request.multiline,
                    minLines = if (request.multiline) 3 else 1,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(value) }) { Text(request.okText) }
        },
        dismissButton = {
            TextButton(onClick = { onResult(null) }) {
                Text(request.cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetDialog(request: DialogRequest.Sheet, onResult: (Any?) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onResult(null) },
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .navigationBarsPadding()
        ) {
            if (request.title.isNotEmpty()) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            request.items.forEachIndexed { index, item ->
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResult(index) }
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
            Text(
                text = request.cancelText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResult(null) }
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            )
        }
    }
}
