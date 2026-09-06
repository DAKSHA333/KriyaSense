package com.kriyasense.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp
)
val KriyaTypography = Typography(
    displayLarge = type(64, 68, FontWeight.Bold),
    displayMedium = type(48, 52, FontWeight.Bold),
    displaySmall = type(40, 44, FontWeight.Bold),
    headlineLarge = type(32, 38, FontWeight.Bold),
    headlineMedium = type(28, 34, FontWeight.Bold),
    headlineSmall = type(24, 30, FontWeight.Bold),
    titleLarge = type(22, 28, FontWeight.SemiBold),
    titleMedium = type(17, 24, FontWeight.SemiBold),
    bodyLarge = type(16, 24), bodyMedium = type(15, 22), bodySmall = type(13, 19),
    labelLarge = type(15, 20, FontWeight.SemiBold),
    labelMedium = type(13, 18, FontWeight.SemiBold),
    labelSmall = type(11, 16, FontWeight.SemiBold)
)
