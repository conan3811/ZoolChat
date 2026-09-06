package com.zoolchat.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF008069),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9FDD3),
    onPrimaryContainer = ZoolTextLight,
    secondary = Color(0xFF25D366),
    secondaryContainer = Color(0xFFD9FDD3),
    onSecondaryContainer = ZoolTextLight,
    tertiary = ZoolGold,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F2F5),
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF30343A),
    outlineVariant = Color(0xFFE4E8EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF00A884),
    onPrimary = Color.White,
    primaryContainer = ZoolBubbleMineDark,
    onPrimaryContainer = ZoolTextDark,
    secondary = Color(0xFF25D366),
    secondaryContainer = ZoolBubbleMineDark,
    onSecondaryContainer = ZoolTextDark,
    tertiary = ZoolGold,
    background = ZoolDark,
    onBackground = Color.White,
    surface = ZoolNavy,
    surfaceVariant = ZoolHeaderDark,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFE9EDEF),
    outlineVariant = Color(0xFF26343C),
)

@Composable
fun ZoolChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ZoolTypography,
        shapes = ZoolShapes,
        content = content,
    )
}
