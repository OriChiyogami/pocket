package io.pocket.host.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

val PocketPurple = Color(0xFF7C5CFF)
val PocketPink = Color(0xFFFF6B9D)
val PocketMint = Color(0xFF2ED573)

private val LightScheme = lightColorScheme(
    primary = PocketPurple,
    onPrimary = Color.White,
    secondary = PocketPink,
    background = Color(0xFFF7F6FA),
    onBackground = Color(0xFF14121C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14121C),
    surfaceVariant = Color(0xFFEDEAF5),
    onSurfaceVariant = Color(0xFF6B6780),
    outline = Color(0xFFDDD8EA),
    error = Color(0xFFE5484D)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9A82FF),
    onPrimary = Color.White,
    secondary = PocketPink,
    background = Color(0xFF0C0B10),
    onBackground = Color(0xFFF2F0F7),
    surface = Color(0xFF17151F),
    onSurface = Color(0xFFF2F0F7),
    surfaceVariant = Color(0xFF252232),
    onSurfaceVariant = Color(0xFF9C97B0),
    outline = Color(0xFF322E42),
    error = Color(0xFFFF6B6B)
)

private val PocketTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = PocketTypography,
        content = content
    )
}

fun parseAccent(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    PocketPurple
}
