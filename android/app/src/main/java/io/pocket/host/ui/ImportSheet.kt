package io.pocket.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pocket.host.ui.theme.PocketStroke
import io.pocket.host.ui.theme.PocketSub
import io.pocket.host.ui.theme.PocketText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onPasteHtml: () -> Unit,
    onLanImport: () -> Unit,
    onRestoreExamples: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = "添加小玩意",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = PocketText
            )
            Text(
                text = "支持导入 .pkt 插件包，也可以直接把一段 HTML 存成应用",
                fontSize = 10.5.sp,
                color = PocketSub,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            ActionRow(
                style = GlyphStyle(GlyphKind.Files, Color(0xFF52D6BE), Color(0xFF22B58F)),
                title = "从文件导入",
                subtitle = "选择 .pkt / .zip 插件包",
                onClick = onPickFile
            )
            ActionRow(
                style = GlyphStyle(GlyphKind.Transfer, Color(0xFF63A0FF), Color(0xFF3F7DFF)),
                title = "从电脑导入",
                subtitle = "同一 Wi-Fi 下自动发现电脑端服务",
                onClick = onLanImport
            )
            ActionRow(
                style = GlyphStyle(GlyphKind.Ai, Color(0xFF7A9BFF), Color(0xFF4F6BE8)),
                title = "粘贴 HTML 新建",
                subtitle = "把 AI 生成的单文件页面直接存成应用",
                onClick = onPasteHtml
            )
            ActionRow(
                style = GlyphStyle(GlyphKind.Motion, Color(0xFFF7C27A), Color(0xFFE99A4C)),
                title = "恢复示例应用",
                subtitle = "重新安装内置的三个示例",
                onClick = onRestoreExamples
            )
        }
    }
}

@Composable
private fun ActionRow(
    style: GlyphStyle,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(1.dp, PocketStroke, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphTile(style = style, size = 42.dp, modifier = Modifier.size(42.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketText
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = PocketSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
