package com.stsmn.monitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

// Deep-space dark scheme: near-black blue surfaces, one electric cyan-blue accent, white text. No gradients.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4DB8FF),
    onPrimary = Color(0xFF031422),
    secondary = Color(0xFF8FD3FF),
    background = Color(0xFF05080F),
    onBackground = Color(0xFFEAF0FA),
    surface = Color(0xFF0B111D),
    onSurface = Color(0xFFEAF0FA),
    surfaceVariant = Color(0xFF121A2A),
    onSurfaceVariant = Color(0xFF9AA8C0),
    outline = Color(0xFF1F2B42),
    outlineVariant = Color(0xFF172033),
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
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = StsmnTypography, content = content)
}

/** One type scale for the whole app: system font, clear weight steps, slightly open tracking on small caps-style labels. */
private val StsmnTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
)
