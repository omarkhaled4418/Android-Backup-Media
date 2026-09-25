package com.hanek.telegrambackup.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0088CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EEFF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF4DA6E8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEFFF),
    onSecondaryContainer = Color(0xFF001C33),
    background = Color(0xFFF8FBFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8FBFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E4E9),
    onSurfaceVariant = Color(0xFF43474D),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82CFFF),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF004C6E),
    onPrimaryContainer = Color(0xFFD4EEFF),
    secondary = Color(0xFFAAD4F5),
    onSecondary = Color(0xFF0F3553),
    secondaryContainer = Color(0xFF2A4C6B),
    onSecondaryContainer = Color(0xFFDBEFFF),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474D),
    onSurfaceVariant = Color(0xFFC3C7CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun TelegramBackupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
