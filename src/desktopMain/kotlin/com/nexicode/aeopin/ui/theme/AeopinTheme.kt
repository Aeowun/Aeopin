/*
 * AEOPIN — Local Capture & Search
 * Copyright (C) 2026 Aeowun
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
