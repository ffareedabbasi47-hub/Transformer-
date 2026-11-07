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

private fun darkScheme(pal: Palette) = darkColorScheme(
    primary = pal.accent,
    onPrimary = pal.onAccent,
    secondary = Color(0xFFB8C0C8),
    tertiary = Color(0xFF9C78C9),
    background = pal.base,
    onBackground = Color(0xFFF1EEE5),
    surface = pal.card,
    onSurface = Color(0xFFF1EEE5),
    surfaceVariant = androidx.compose.ui.graphics.lerp(pal.card, Color.White, 0.06f),
    onSurfaceVariant = Color(0xFFB0ACB8),
    outline = pal.outline,
    outlineVariant = pal.outline.copy(alpha = 0.6f),
    error = Color(0xFFD16B70),
)

private fun lightScheme(pal: Palette) = lightColorScheme(
    primary = pal.accent,
    onPrimary = pal.onAccent,
    secondary = Color(0xFF5E6770),
    background = pal.base,
    onBackground = Color(0xFF1B1530),
    surface = pal.card,
    onSurface = Color(0xFF1B1530),
    surfaceVariant = androidx.compose.ui.graphics.lerp(pal.base, Color.Black, 0.05f),
    onSurfaceVariant = Color(0xFF5E5A70),
    outline = pal.outline,
    outlineVariant = pal.outline.copy(alpha = 0.6f),
    error = Color(0xFFB0444A),
)

/** Resolves the chosen theme. "System" follows Settings > Display (Light / Dark / System). */
private fun resolvePalette(theme: AppTheme, mode: ThemeMode, systemDark: Boolean): Palette =
    if (theme != AppTheme.SYSTEM) theme.palette else when (mode) {
        ThemeMode.LIGHT -> AppTheme.IVORY.palette
        ThemeMode.DARK -> AppTheme.SYSTEM.palette
        ThemeMode.SYSTEM -> if (systemDark) AppTheme.SYSTEM.palette else AppTheme.IVORY.palette
    }

@Composable
fun StsmnTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = LocalUiPrefsStore.current
    val prefs = store?.current ?: UiPrefs()
    val pal = resolvePalette(prefs.theme, mode, isSystemInDarkTheme())
    val reduce = prefs.reduceMotion || systemAnimationsOff(ctx)
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPalette provides pal,
        LocalCardRadius provides prefs.corners.radius,
        LocalReduceMotion provides reduce,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * prefs.textSize.factor),
    ) {
        MaterialTheme(colorScheme = if (pal.light) lightScheme(pal) else darkScheme(pal), typography = StsmnTypography, content = content)
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
