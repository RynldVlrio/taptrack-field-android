package com.taptrack.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Compact, web-like type scale (web: text-sm 14px body, text-xs 12px labels, semibold headings). */
private val Sans = FontFamily.SansSerif
private fun s(size: Int, line: Int, w: FontWeight = FontWeight.Normal, track: Double = 0.0) =
    TextStyle(fontFamily = Sans, fontWeight = w, fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp)

val Typography = Typography(
    headlineSmall = s(22, 28, FontWeight.SemiBold),
    titleLarge = s(18, 24, FontWeight.SemiBold),
    titleMedium = s(15, 22, FontWeight.SemiBold),
    titleSmall = s(13, 18, FontWeight.SemiBold),
    bodyLarge = s(15, 22),
    bodyMedium = s(14, 20),
    bodySmall = s(12, 16),
    labelLarge = s(14, 20, FontWeight.Medium),
    labelMedium = s(12, 16, FontWeight.Medium),
    labelSmall = s(11, 14, FontWeight.Medium),
)
