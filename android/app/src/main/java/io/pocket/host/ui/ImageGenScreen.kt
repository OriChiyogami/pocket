package io.pocket.host.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.pocket.host.core.ImageConfig
import io.pocket.host.ui.theme.PocketPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 画图页：提示词 + 参考图 → 中转站生图 → 保存到手机相册 / 分享。
 */
@Composable
fun ImageGenScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    imageSummary: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configured = imageSummary.isNotBlank() && imageSummary != "未配置"

    var prompt by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("auto") }
    var quality by remember { mutableStateOf("auto") }
    var count by remember { mutableStateOf(1) }
    val refs = remember { mutableStateListOf<Uri>() }
    val results = remember { mutableStateListOf<File>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var elapsed by remember { mutableStateOf(0) }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.take((4 - refs.size).coerceAtLeast(0)).forEach { refs.add(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingSave
        pendingSave = null
        if (granted && file != null) {
            doSave(context, file)
        } else {
            Toast.makeText(context, "没有存储权限，无法保存到相册", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestSave(file: File) {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (legacy) {
            pendingSave = file
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doSave(context, file)
        }
    }

    fun generate() {
        val text = prompt.trim()
        if (text.isEmpty() || busy) return
        busy = true
        error = ""
        results.clear()
        val refUris = refs.toList()
        val (s, q, n) = Triple(size, quality, count)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val refImages = refUris.mapNotNull { uri ->
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(uri)
                                ?.use { it.readBytes() } ?: return@runCatching null
                            ImageConfig.RefImage(
                                bytes = bytes,
                                mime = context.contentResolver.getType(uri) ?: "image/png"
                            )
                        }.getOrNull()
                    }
                    ImageConfig.generate(
                        context = context,
                        prompt = text,
                        size = s,
                        quality = q,
                        count = n,
                        refs = refImages
                    )
                }
            }
            busy = false
            result.fold(
                onSuccess = { files ->
                    results.addAll(files)
                    if (files.isEmpty()) error = "没有拿到图片，换个提示词再试"
                },
                onFailure = { error = it.message ?: "生成失败" }
            )
        }
    }

    LaunchedEffect(busy) {
        if (busy) {
            elapsed = 0
            while (true) {
                delay(1000)
                elapsed += 1
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "画图",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (configured) imageSummary else "未配置生图模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (results.isNotEmpty()) {
                TextButton(onClick = { results.clear(); error = "" }) { Text("清空") }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("提示词") },
                placeholder = { Text("想要什么画面？例如：雨后的赛博朋克街道，霓虹倒影，电影感打光") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "参考图",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = if (refs.isEmpty()) "可不填，最多 4 张" else "${refs.size}/4",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (refs.isNotEmpty()) {
                    TextButton(onClick = { refs.clear() }) { Text("全部移除") }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                refs.forEachIndexed { index, uri ->
                    RefThumb(uri = uri, onRemove = { refs.removeAt(index) })
                }
                if (refs.size < 4) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { picker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "＋",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ChoiceRow(
                title = "尺寸",
                options = ImageConfig.SIZES,
                selected = size,
                labelOf = { if (it == "auto") "自动" else it.replace("x", " × ") },
                onSelect = { size = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            ChoiceRow(
                title = "质量",
                options = ImageConfig.QUALITIES,
                selected = quality,
                labelOf = {
                    when (it) {
                        "auto" -> "自动"
                        "low" -> "低"
                        "medium" -> "中"
                        else -> "高"
                    }
                },
                onSelect = { quality = it }
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "数量",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                (1..4).forEach { value ->
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (value == count) PocketPurple
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                            .clickable { count = value },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (value == count) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!configured) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(PocketPurple.copy(alpha = 0.1f))
                        .clickable(onClick = onOpenSettings)
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "先配置生图模型",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PocketPurple
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "填中转站地址和 Key 后就能画图，点这里去配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (error.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { generate() },
                enabled = prompt.isNotBlank() && !busy && configured,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PocketPurple),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text("生成中 ${elapsed}s")
                } else {
                    Text("生成图片")
                }
            }

            if (busy && elapsed >= 15) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "高质量大图通常要 30～90 秒，请别退出这个页面。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "结果",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                results.forEachIndexed { index, file ->
                    ResultCard(
                        file = file,
                        index = index,
                        onSave = { requestSave(file) },
                        onShare = { shareImage(context, file) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun RefThumb(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(modifier = Modifier.size(76.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "参考图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除",
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    labelOf: (String) -> String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val active = option == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) PocketPurple.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = labelOf(option),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) PocketPurple
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    file: File,
    index: Int,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val bitmap by produceState<ImageBitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampled(file)?.asImageBitmap() }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "生成结果 ${index + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height.toFloat())
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = PocketPurple
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 ${index + 1} 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onShare) { Text("分享") }
            Spacer(modifier = Modifier.size(4.dp))
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PocketPurple)
            ) { Text("保存到相册") }
        }
    }
}

// ------------------------------------------------------------------ 文件操作

private fun decodeSampled(file: File, maxDim: Int = 1400): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

private fun doSave(context: Context, file: File) {
    val uri = runCatching { saveToGallery(context, file) }.getOrNull()
    Toast.makeText(
        context,
        if (uri != null) "已保存到相册 Pictures/Pocket" else "保存失败",
        Toast.LENGTH_SHORT
    ).show()
}

private fun saveToGallery(context: Context, file: File): Uri? {
    val resolver = context.contentResolver
    val name = "pocket_${System.currentTimeMillis()}.${file.extension}"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(file))
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pocket")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Pocket"
    )
    dir.mkdirs()
    val target = File(dir, name)
    file.copyTo(target, overwrite = true)
    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
    return Uri.fromFile(target)
}

private fun shareImage(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }.onFailure {
        Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}
