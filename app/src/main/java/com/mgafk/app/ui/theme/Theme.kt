package com.mgafk.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette
val BgDark = Color(0xFF0B0F14)
val SurfaceDark = Color(0xFF131921)
val SurfaceCard = Color(0xFF17202B)
val SurfaceBorder = Color(0xFF1F2C3A)
val Accent = Color(0xFF6C8CFF)
val AccentDim = Color(0xFF3A4F7A)
val TextPrimary = Color(0xFFE8ECF0)
val TextSecondary = Color(0xFF8896A6)
val TextMuted = Color(0xFF5A6978)

val StatusConnected = Color(0xFF4ADE80)
val StatusConnecting = Color(0xFFFBBF24)
val StatusError = Color(0xFFF87171)
val StatusIdle = Color(0xFF64748B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentDim,
    secondary = StatusConnected,
    onSecondary = Color.Black,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = Color.White,
    outline = SurfaceBorder,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp,
        color = TextPrimary,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = (-0.2).sp,
        color = TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        color = TextPrimary,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        color = TextSecondary,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = TextMuted,
    ),
)

@Composable
fun MgAfkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
