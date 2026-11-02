package com.taptrack.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Same palette as the web app (Tailwind teal + slate). */
object Web {
    val Teal50 = Color(0xFFF0FDFA)
    val Teal100 = Color(0xFFCCFBF1)
    val Teal600 = Color(0xFF0D9488)
    val Teal700 = Color(0xFF0F766E)   // primary (btn-primary, logo)
    val Teal800 = Color(0xFF115E59)   // headings / active nav text
    val Slate50 = Color(0xFFF8FAFC)
    val Slate100 = Color(0xFFF1F5F9)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)
    val Slate600 = Color(0xFF475569)
    val Slate700 = Color(0xFF334155)
    val Slate900 = Color(0xFF0F172A)
    val Rose50 = Color(0xFFFFF1F2)
    val Rose600 = Color(0xFFE11D48)
    val Amber50 = Color(0xFFFFFBEB)
    val Amber700 = Color(0xFFB45309)
}

private val WebColors = lightColorScheme(
    primary = Web.Teal700,
    onPrimary = Color.White,
    primaryContainer = Web.Teal50,
    onPrimaryContainer = Web.Teal800,
    secondary = Web.Slate600,
    onSecondary = Color.White,
    secondaryContainer = Web.Teal50,          // selected nav item / chips (bg-teal-50)
    onSecondaryContainer = Web.Teal800,
    tertiary = Web.Teal600,
    onTertiary = Color.White,
    tertiaryContainer = Web.Teal100,
    onTertiaryContainer = Web.Teal800,
    error = Web.Rose600,
    onError = Color.White,
    errorContainer = Web.Rose50,
    onErrorContainer = Color(0xFF9F1239),
    background = Web.Slate50,                 // page background
    onBackground = Web.Slate900,
    surface = Color.White,                    // cards, bars, sheets
    onSurface = Web.Slate900,
    surfaceVariant = Web.Slate100,
    onSurfaceVariant = Web.Slate500,
    surfaceTint = Color.White,                // no tinted elevation
    outline = Web.Slate300,
    outlineVariant = Web.Slate200,
    inverseSurface = Web.Slate900,
    inverseOnSurface = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Web.Slate100,
)

/** Web uses rounded-md (6px) / rounded-lg (8px) corners. */
private val WebShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/** Light theme only, like the web app (the system dark mode is ignored on purpose). */
@Composable
fun TapTrackTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(colorScheme = WebColors, typography = Typography, shapes = WebShapes, content = content)
}
