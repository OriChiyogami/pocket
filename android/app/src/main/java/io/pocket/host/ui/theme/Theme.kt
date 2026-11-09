package io.pocket.host.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 手机端黑 / 白 / 灰极简配色（参考卡片式 App 设计）。
 * 变量名保留是为了不改动已有页面。
 */
val PocketPurple = Color(0xFF141414)
val PocketPink = Color(0xFF6B6B6B)
val PocketMint = Color(0xFF141414)

val PocketDeep = Color(0xFFF3F5F8)
val PocketSurface = Color(0xFFFFFFFF)
val PocketSurfaceHigh = Color(0xFFEFEFEC)
val PocketStroke = Color(0xFFE7EAF0)
val PocketText = Color(0xFF141414)
val PocketSub = Color(0xFF69707F)

private val MonoScheme = lightColorScheme(
    primary = Color(0xFF141414),
    onPrimary = Color.White,
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color.White,
    background = PocketDeep,
    onBackground = PocketText,
    surface = PocketSurface,
    onSurface = PocketText,
    surfaceVariant = PocketSurfaceHigh,
    onSurfaceVariant = PocketSub,
    outline = PocketStroke,
    error = Color(0xFFD93025)
)

private val PocketTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun PocketTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MonoScheme,
        typography = PocketTypography,
        content = content
    )
}

fun parseAccent(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color(0xFF141414)
}
