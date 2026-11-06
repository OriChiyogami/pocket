package io.pocket.host.runner

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.pocket.host.core.InstalledApp
import io.pocket.host.ui.theme.parseAccent

@Composable
fun RunnerScreen(
    app: InstalledApp,
    progress: Int,
    loading: Boolean,
    safeArea: SafeArea,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    edgeToEdge: Boolean,
    webView: WebView
) {
    val accent = parseAccent(app.accent)
    val background = runCatching { Color(android.graphics.Color.parseColor(app.background)) }
        .getOrElse { Color.Black }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )

        // 默认模式：状态栏/导航栏区域由系统占位，页面从安全区之内的“浏览器视口”开始
        val handleTop = if (edgeToEdge) (safeArea.top + 7).dp else 4.dp
        val barTop = if (edgeToEdge) safeArea.top else 0
        val handleColor = if (background.luminance() > 0.6f) {
            Color.Black.copy(alpha = 0.18f)
        } else {
            Color.White.copy(alpha = 0.24f)
        }

        if (loading && progress in 1..99) {
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .padding(top = barTop.dp),
                color = accent,
                trackColor = Color.Transparent
            )
        }

        if (!controlsVisible) {
            // 顶部细条：视觉上很轻，但保留足够大的可点区域，点一下呼出控制条
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = handleTop)
                    .width(56.dp)
                    .height(22.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { onToggleControls() }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(handleColor)
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ControlBar(
                title = app.name,
                accent = accent,
                topInset = barTop,
                onBack = onBack,
                onReload = onReload,
                onClose = onClose
            )
        }
    }
}

@Composable
private fun ControlBar(
    title: String,
    accent: Color,
    topInset: Int,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = (topInset + 10).dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE61A1822))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFFEDEBF5))
        }

        Spacer(modifier = Modifier.width(2.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFF4F2FA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
                .padding(end = 4.dp)
        )

        IconButton(onClick = onReload, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Color(0xFFB9B4C9))
        }

        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFFB9B4C9))
        }
    }
}
