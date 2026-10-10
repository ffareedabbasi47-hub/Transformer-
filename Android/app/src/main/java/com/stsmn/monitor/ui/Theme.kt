package com.stsmn.monitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stsmn.monitor.data.ThemeMode
import com.stsmn.monitor.domain.AlertLevel
import com.stsmn.monitor.domain.SystemStatus

object StsmnColors {
    val Safe = Color(0xFF2ECC71)
    val Warning = Color(0xFFF5A623)
    val Emergency = Color(0xFFE5484D)
    val Stale = Color(0xFF8A94A6)
    val Info = Color(0xFF4C8DFF)
}

fun statusColor(s: SystemStatus): Color = when (s) {
    SystemStatus.SAFE -> StsmnColors.Safe
    SystemStatus.WARNING -> StsmnColors.Warning
    SystemStatus.EMERGENCY -> StsmnColors.Emergency
}

fun alertColor(l: AlertLevel): Color = when (l) {
    AlertLevel.INFO -> StsmnColors.Info
    AlertLevel.WARNING -> StsmnColors.Warning
    AlertLevel.CRITICAL -> StsmnColors.Emergency
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5AA9FF),
    onPrimary = Color(0xFF04213D),
    background = Color(0xFF070E1C),
    onBackground = Color(0xFFE8EEF8),
    surface = Color(0xFF0E1A2E),
    onSurface = Color(0xFFE8EEF8),
    surfaceVariant = Color(0xFF16253F),
    onSurfaceVariant = Color(0xFFA9B6CC),
    outline = Color(0xFF2A3B5C),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B63C9),
    onPrimary = Color.White,
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF0F1A2E),
    surface = Color.White,
    onSurface = Color(0xFF0F1A2E),
    surfaceVariant = Color(0xFFE6ECF7),
    onSurfaceVariant = Color(0xFF4A5870),
    outline = Color(0xFFB8C3D6),
)

@Composable
fun StsmnTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
