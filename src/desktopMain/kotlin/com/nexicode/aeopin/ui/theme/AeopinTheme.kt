package com.nexicode.aeopin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Atmospheric Palette
val AeopinTurquoise = Color(0xFF1BC4C4)
val AeopinMidnight = Color(0xFF0F111A)
val AeopinDeepSlate = Color(0xFF1B1E2B)
val AeopinSurface = Color(0xFF242938)
val AeopinTextPrimary = Color(0xFFE1E5F2)
val AeopinTextSecondary = Color(0xFF8B949E)
val AeopinAccentGlow = Color(0xFF1BC4C4).copy(alpha = 0.15f)

val SegoeUI = FontFamily.Default 

val AeopinTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = SegoeUI,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.2).sp,
        color = AeopinTextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = SegoeUI,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = AeopinTextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = SegoeUI,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = AeopinTextPrimary
    ),
    bodySmall = TextStyle(
        fontFamily = SegoeUI,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        color = AeopinTextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = SegoeUI,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        color = AeopinTextPrimary,
        letterSpacing = 1.sp
    )
)

@Composable
fun AeopinTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = AeopinTurquoise,
        onPrimary = AeopinMidnight,
        surface = AeopinDeepSlate,
        onSurface = AeopinTextPrimary,
        background = AeopinMidnight,
        onBackground = AeopinTextPrimary,
        outline = AeopinTurquoise.copy(alpha = 0.2f),
        surfaceVariant = AeopinSurface
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AeopinTypography,
        content = content
    )
}
