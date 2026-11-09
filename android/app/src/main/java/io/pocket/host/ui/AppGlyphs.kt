package io.pocket.host.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Dp
import io.pocket.host.core.InstalledApp

/**
 * 应用图标：一块柔和的彩色底 + 一个白色 MG 图形。
 * 图形都按 24×24 网格设计，随容器缩放，替代原来「只放一个大字」的占位。
 */
enum class GlyphKind { Transfer, Clipboard, Files, Ai, Image, Hello, Motion, Tool, Folder, Generic }

data class GlyphStyle(
    val kind: GlyphKind,
    val start: Color,
    val end: Color
)

private val VioletStart = Color(0xFF8B7CF6)
private val VioletEnd = Color(0xFF6C63E8)
private val BlueStart = Color(0xFF63A0FF)
private val BlueEnd = Color(0xFF3F7DFF)
private val MintStart = Color(0xFF52D6BE)
private val MintEnd = Color(0xFF22B58F)
private val CoralStart = Color(0xFFFF9C86)
private val CoralEnd = Color(0xFFF26E6E)
private val PinkStart = Color(0xFFF79AC0)
private val PinkEnd = Color(0xFFE76AA0)
private val AmberStart = Color(0xFFF7C27A)
private val AmberEnd = Color(0xFFE99A4C)
private val IndigoStart = Color(0xFF7A9BFF)
private val IndigoEnd = Color(0xFF4F6BE8)
private val GrayStart = Color(0xFFD7DCE6)
private val GrayEnd = Color(0xFFBFC6D4)

fun glyphStyleFor(app: InstalledApp): GlyphStyle {
    val key = (app.id + " " + app.name).lowercase()
    val kind = when {
        key.contains("transfer") || key.contains("互传") || key.contains("传") -> GlyphKind.Transfer
        key.contains("clipboard") || key.contains("剪贴") -> GlyphKind.Clipboard
        key.contains("files") || key.contains("文件") -> GlyphKind.Files
        key.contains("ai") || key.contains("助手") -> GlyphKind.Ai
        key.contains("draw") || key.contains("image") || key.contains("画") -> GlyphKind.Image
        key.contains("hello") || key.contains("你") -> GlyphKind.Hello
        key.contains("motion") || key.contains("动") -> GlyphKind.Motion
        key.contains("tool") || key.contains("工具") -> GlyphKind.Tool
        key.contains("folder") || key.contains("文件夹") -> GlyphKind.Folder
        else -> GlyphKind.Generic
    }
    return when (kind) {
        GlyphKind.Transfer -> GlyphStyle(kind, BlueStart, BlueEnd)
        GlyphKind.Clipboard -> GlyphStyle(kind, VioletStart, VioletEnd)
        GlyphKind.Files -> GlyphStyle(kind, MintStart, MintEnd)
        GlyphKind.Ai -> GlyphStyle(kind, IndigoStart, IndigoEnd)
        GlyphKind.Image -> GlyphStyle(kind, CoralStart, CoralEnd)
        GlyphKind.Hello -> GlyphStyle(kind, PinkStart, PinkEnd)
        GlyphKind.Motion -> GlyphStyle(kind, AmberStart, AmberEnd)
        GlyphKind.Tool -> GlyphStyle(kind, BlueStart, BlueEnd)
        GlyphKind.Folder -> GlyphStyle(kind, GrayStart, GrayEnd)
        GlyphKind.Generic -> GlyphStyle(kind, VioletStart, VioletEnd)
    }
}

@Composable
fun GlyphTile(
    style: GlyphStyle,
    size: Dp,
    modifier: Modifier = Modifier,
    radiusRatio: Float = 0.28f
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(size * radiusRatio))
            .background(Brush.linearGradient(listOf(style.start, style.end)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = this.size.minDimension * 0.24f
            val box = this.size.minDimension - pad * 2
            val scale = box / 24f
            translate(pad, pad) {
                drawGlyph(style.kind, scale)
            }
        }
    }
}

/** 在 24×24 网格里画图形，白色线条 + 轻微填充。 */
private fun DrawScope.drawGlyph(kind: GlyphKind, s: Float) {
    val white = Color.White
    val soft = Color.White.copy(alpha = 0.22f)
    val stroke = s * 1.7f
    val line = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    fun path(vararg pts: Pair<Float, Float>): Path = Path().apply {
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s)
        }
    }

    when (kind) {
        GlyphKind.Transfer -> {
            // 两台设备 + 双向箭头
            drawRoundRect(white.copy(alpha = 0.9f), topLeft = p(3.2f, 3.4f), size = Size(7.2f * s, 5.2f * s), cornerRadius = CornerRadius(1.2f * s), style = Stroke(stroke * 0.9f))
            drawRoundRect(white.copy(alpha = 0.9f), topLeft = p(13.6f, 15.4f), size = Size(7.2f * s, 5.2f * s), cornerRadius = CornerRadius(1.2f * s), style = Stroke(stroke * 0.9f))
            drawLine(white, p(6.8f, 9.6f), p(6.8f, 13.6f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, p(17.2f, 14.4f), p(17.2f, 10.4f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawPath(path(6.8f to 13.6f, 5.4f to 12.2f), white, style = line)
            drawPath(path(6.8f to 13.6f, 8.2f to 12.2f), white, style = line)
            drawPath(path(17.2f to 10.4f, 15.8f to 11.8f), white, style = line)
            drawPath(path(17.2f to 10.4f, 18.6f to 11.8f), white, style = line)
        }

        GlyphKind.Clipboard -> {
            drawRoundRect(soft, topLeft = p(5f, 4.2f), size = Size(14f * s, 16.4f * s), cornerRadius = CornerRadius(2.4f * s))
            drawRoundRect(white, topLeft = p(5f, 4.2f), size = Size(14f * s, 16.4f * s), cornerRadius = CornerRadius(2.4f * s), style = Stroke(stroke))
            drawRoundRect(white, topLeft = p(8.6f, 2.4f), size = Size(6.8f * s, 3.4f * s), cornerRadius = CornerRadius(1.4f * s), style = Stroke(stroke * 0.85f))
            drawLine(white, p(8.4f, 10.4f), p(15.6f, 10.4f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, p(8.4f, 14f), p(13.4f, 14f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }

        GlyphKind.Files -> {
            // 文件夹 + 一页纸
            val folder = Path().apply {
                moveTo(3.2f * s, 8.4f * s)
                lineTo(3.2f * s, 6.4f * s)
                quadraticBezierTo(3.2f * s, 5f * s, 4.6f * s, 5f * s)
                lineTo(8.6f * s, 5f * s)
                lineTo(10.4f * s, 7f * s)
                lineTo(18.8f * s, 7f * s)
                quadraticBezierTo(20.8f * s, 7f * s, 20.8f * s, 9f * s)
                lineTo(20.8f * s, 17.4f * s)
                quadraticBezierTo(20.8f * s, 19.4f * s, 18.8f * s, 19.4f * s)
                lineTo(5.2f * s, 19.4f * s)
                quadraticBezierTo(3.2f * s, 19.4f * s, 3.2f * s, 17.4f * s)
                close()
            }
            drawPath(folder, soft)
            drawPath(folder, white, style = Stroke(stroke))
            drawLine(white, p(7.6f, 12.4f), p(16.4f, 12.4f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, p(7.6f, 15.4f), p(13.4f, 15.4f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }

        GlyphKind.Ai -> {
            // 四角星 + 小星
            val star = Path().apply {
                moveTo(9.2f * s, 3.4f * s)
                lineTo(10.9f * s, 8.0f * s)
                lineTo(15.5f * s, 9.7f * s)
                lineTo(10.9f * s, 11.4f * s)
                lineTo(9.2f * s, 16f * s)
                lineTo(7.5f * s, 11.4f * s)
                lineTo(2.9f * s, 9.7f * s)
                lineTo(7.5f * s, 8.0f * s)
                close()
            }
            drawPath(star, white)
            val small = Path().apply {
                moveTo(16.4f * s, 13.6f * s)
                lineTo(17.5f * s, 16.4f * s)
                lineTo(20.2f * s, 17.5f * s)
                lineTo(17.5f * s, 18.6f * s)
                lineTo(16.4f * s, 21.3f * s)
                lineTo(15.3f * s, 18.6f * s)
                lineTo(12.6f * s, 17.5f * s)
                lineTo(15.3f * s, 16.4f * s)
                close()
            }
            drawPath(small, white.copy(alpha = 0.55f))
        }

        GlyphKind.Image -> {
            drawRoundRect(soft, topLeft = p(3.2f, 4.6f), size = Size(17.6f * s, 14.8f * s), cornerRadius = CornerRadius(2.6f * s))
            drawRoundRect(white, topLeft = p(3.2f, 4.6f), size = Size(17.6f * s, 14.8f * s), cornerRadius = CornerRadius(2.6f * s), style = Stroke(stroke))
            drawCircle(white, 1.5f * s, p(8.4f, 9.2f))
            drawPath(path(4.6f to 17.6f, 10.2f to 11.4f, 15.4f to 17.6f), white, style = Stroke(stroke * 0.85f))
            drawPath(path(12.6f to 17.4f, 15.8f to 13.6f, 19.6f to 17.4f), white.copy(alpha = 0.6f), style = Stroke(stroke * 0.7f))
        }

        GlyphKind.Hello -> {
            // 对话气泡
            val bubble = Path().apply {
                moveTo(4.4f * s, 6.4f * s)
                quadraticBezierTo(4.4f * s, 4.4f * s, 6.6f * s, 4.4f * s)
                lineTo(17.4f * s, 4.4f * s)
                quadraticBezierTo(19.6f * s, 4.4f * s, 19.6f * s, 6.4f * s)
                lineTo(19.6f * s, 13.4f * s)
                quadraticBezierTo(19.6f * s, 15.4f * s, 17.4f * s, 15.4f * s)
                lineTo(10.4f * s, 15.4f * s)
                lineTo(6.4f * s, 19.2f * s)
                lineTo(6.6f * s, 15.4f * s)
                lineTo(6.6f * s, 15.4f * s)
                quadraticBezierTo(4.4f * s, 15.4f * s, 4.4f * s, 13.4f * s)
                close()
            }
            drawPath(bubble, soft)
            drawPath(bubble, white, style = Stroke(stroke))
            drawCircle(white, 1.1f * s, p(9.2f, 10f))
            drawCircle(white, 1.1f * s, p(12f, 10f))
            drawCircle(white, 1.1f * s, p(14.8f, 10f))
        }

        GlyphKind.Motion -> {
            drawCircle(soft, 9.4f * s, p(12f, 12f))
            drawCircle(white, 9.4f * s, p(12f, 12f), style = Stroke(stroke))
            drawPath(path(9.8f to 8.2f, 16.2f to 12f, 9.8f to 15.8f), white)
        }

        GlyphKind.Tool -> {
            // 滑块
            drawLine(white, p(4.4f, 7f), p(19.6f, 7f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, p(4.4f, 12f), p(19.6f, 12f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, p(4.4f, 17f), p(19.6f, 17f), stroke * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(white, 2.3f * s, p(8.4f, 7f))
            drawCircle(white, 2.3f * s, p(15.4f, 12f))
            drawCircle(white, 2.3f * s, p(10.4f, 17f))
        }

        GlyphKind.Folder -> {
            val folder = Path().apply {
                moveTo(3.4f * s, 9f * s)
                lineTo(3.4f * s, 6.6f * s)
                quadraticBezierTo(3.4f * s, 5f * s, 5f * s, 5f * s)
                lineTo(9f * s, 5f * s)
                lineTo(11f * s, 7.4f * s)
                lineTo(19f * s, 7.4f * s)
                quadraticBezierTo(20.8f * s, 7.4f * s, 20.8f * s, 9.2f * s)
                lineTo(20.8f * s, 17.4f * s)
                quadraticBezierTo(20.8f * s, 19.2f * s, 19f * s, 19.2f * s)
                lineTo(5.2f * s, 19.2f * s)
                quadraticBezierTo(3.4f * s, 19.2f * s, 3.4f * s, 17.4f * s)
                close()
            }
            drawPath(folder, soft)
            drawPath(folder, white, style = Stroke(stroke))
            drawLine(white.copy(alpha = 0.7f), p(3.8f, 11.2f), p(20.4f, 11.2f), stroke * 0.7f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }

        GlyphKind.Generic -> {
            drawRoundRect(white.copy(alpha = 0.9f), topLeft = p(4f, 4f), size = Size(6.6f * s, 6.6f * s), cornerRadius = CornerRadius(1.8f * s), style = Stroke(stroke * 0.9f))
            drawRoundRect(white.copy(alpha = 0.9f), topLeft = p(13.4f, 4f), size = Size(6.6f * s, 6.6f * s), cornerRadius = CornerRadius(1.8f * s), style = Stroke(stroke * 0.9f))
            drawRoundRect(white.copy(alpha = 0.9f), topLeft = p(4f, 13.4f), size = Size(6.6f * s, 6.6f * s), cornerRadius = CornerRadius(1.8f * s), style = Stroke(stroke * 0.9f))
            drawRoundRect(white.copy(alpha = 0.9f), topLeft = p(13.4f, 13.4f), size = Size(6.6f * s, 6.6f * s), cornerRadius = CornerRadius(1.8f * s), style = Stroke(stroke * 0.9f))
        }
    }
}

/** 文件夹/空态用的小图标（灰底白色） */
@Composable
fun FolderGlyph(size: Dp, modifier: Modifier = Modifier) {
    val style = remember { GlyphStyle(GlyphKind.Folder, GrayStart, GrayEnd) }
    GlyphTile(style = style, size = size, modifier = modifier)
}
