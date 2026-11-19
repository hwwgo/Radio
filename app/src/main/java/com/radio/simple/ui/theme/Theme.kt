package com.radio.simple.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Android 12 以上直接用系统壁纸取色（Material You），
 * 深色 / 浅色跟随系统设置；低版本用下面这套暖色兜底。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFB4462A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF3F0B00),
    secondary = Color(0xFF77574C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBD1),
    onSecondaryContainer = Color(0xFF2C150E),
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF231917),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED7),
    onSurfaceVariant = Color(0xFF53433F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF4F0),
    surfaceContainer = Color(0xFFF9ECE7),
    surfaceContainerHigh = Color(0xFFF3E5E0),
    surfaceContainerHighest = Color(0xFFEDE0DB),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB5A0),
    onPrimary = Color(0xFF601400),
    primaryContainer = Color(0xFF8A2B12),
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFE7BEB1),
    onSecondary = Color(0xFF442A21),
    secondaryContainer = Color(0xFF5D4036),
    onSecondaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFF191110),
    onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF191110),
    onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BB),
    surfaceContainerLowest = Color(0xFF130C0A),
    surfaceContainerLow = Color(0xFF211A18),
    surfaceContainer = Color(0xFF251E1C),
    surfaceContainerHigh = Color(0xFF302826),
    surfaceContainerHighest = Color(0xFF3B3330),
    outline = Color(0xFFA08C86),
    outlineVariant = Color(0xFF53433F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun RadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
