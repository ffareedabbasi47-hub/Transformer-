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
    // AURELION status palette: muted, but Warning (amber) and Emergency (rose red) stay instantly distinguishable.
    val Safe = Color(0xFF4C9B78)
    val Warning = Color(0xFFD6A34F)
    val Emergency = Color(0xFFD16B70)
    val Stale = Color(0xFF8A9099)
    val Info = Color(0xFF7FA6C9)
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

private fun darkScheme(p: UiPrefs) = darkColorScheme(
    primary = p.accent.color,
    onPrimary = Color(0xFF14110A),
    secondary = Color(0xFFB8C0C8),                    // muted platinum
    tertiary = Color(0xFF9C78C9),                     // amethyst
    background = p.surface.base,
    onBackground = Color(0xFFF1EEE5),
    surface = p.surface.raised,
    onSurface = Color(0xFFF1EEE5),
    surfaceVariant = p.surface.high,
    onSurfaceVariant = Color(0xFFA7A39B),
    outline = p.surface.outline,
    outlineVariant = p.surface.outline.copy(alpha = 0.6f),
    error = Color(0xFFD16B70),
)

// "Ivory & platinum" light theme: warm paper background, the same accent family darkened for contrast.
private fun lightScheme(p: UiPrefs) = lightColorScheme(
    primary = accentForLight(p.accent.color),
    onPrimary = Color.White,
    secondary = Color(0xFF5E6770),
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF17140F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17140F),
    surfaceVariant = Color(0xFFEAE5D9),
    onSurfaceVariant = Color(0xFF5E5A52),
    outline = Color(0xFFD8D2C4),
    outlineVariant = Color(0xFFE6E1D5),
    error = Color(0xFFB0444A),
)

@Composable
fun StsmnTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = LocalUiPrefsStore.current
    val prefs = store?.current ?: UiPrefs()
    val reduce = prefs.reduceMotion || systemAnimationsOff(ctx)
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCardRadius provides prefs.corners.radius,
        LocalReduceMotion provides reduce,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * prefs.textSize.factor),
    ) {
        MaterialTheme(colorScheme = if (dark) darkScheme(prefs) else lightScheme(prefs), typography = StsmnTypography, content = content)
    }
}

/** One type scale for the whole app: system font, clear weight steps, slightly open tracking on small caps-style labels. */
private val StsmnTypography = Typography(
    headlineMedium = TextStyle(fontFamily = AurelionType.heading, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = AurelionType.heading, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = AurelionType.heading, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp),
)
