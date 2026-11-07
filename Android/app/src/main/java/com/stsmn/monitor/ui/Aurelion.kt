package com.stsmn.monitor.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * STSMN AURELION - Premium Industrial Intelligence. Design tokens + the user-selectable appearance options.
 * Presentation only: nothing here touches data, networking or business logic. Appearance choices are stored in their
 * own SharedPreferences file ("stsmn_ui"), separate from AppSettings.
 */

/** One complete colour style: accent, a page gradient (top tint fading into the dark base), card and outline colours. */
class Palette(
    val accent: Color, val gradTop: Color, val base: Color, val card: Color, val outline: Color,
    val light: Boolean = false, val onAccent: Color = Color(0xFF101010),
)

/** The 12 selectable themes (Settings > Appearance). "System" follows Light/Dark/System in Settings > Display. */
enum class AppTheme(val label: String, val palette: Palette) {
    EMERALD("Emerald Dark", Palette(Color(0xFF0DB77F), Color(0xFF0B4F3D), Color(0xFF050D0B), Color(0xFF0B1512), Color(0xFF1F302A))),
    SAGE("Sage & Grey", Palette(Color(0xFF85A88C), Color(0xFF3C5142), Color(0xFF121817), Color(0xFF161C19), Color(0xFF2B3731))),
    CLAUDE("Claude", Palette(Color(0xFFA688FB), Color(0xFF3C2676), Color(0xFF080612), Color(0xFF0E0A1C), Color(0xFF2A2145))),
    CYBERPUNK("Cyberpunk", Palette(Color(0xFF00EEFF), Color(0xFF0A3550), Color(0xFF04070F), Color(0xFF0A1019), Color(0xFF173246))),
    GOLD("Obsidian Gold", Palette(Color(0xFFF39C0A), Color(0xFF5F3D05), Color(0xFF0B0905), Color(0xFF131009), Color(0xFF3A2D14))),
    OCEAN("Deep Ocean", Palette(Color(0xFF37BCF7), Color(0xFF114F73), Color(0xFF030D17), Color(0xFF07161F), Color(0xFF16364A))),
    SUNSET("Sunset", Palette(Color(0xFFFC6F84), Color(0xFF6B2433), Color(0xFF12070B), Color(0xFF1A0C11), Color(0xFF45222B))),
    NORDIC("Nordic Frost", Palette(Color(0xFF88BFD0), Color(0xFF3B5261), Color(0xFF131921), Color(0xFF171E27), Color(0xFF2E3B48))),
    MOCHA("Mocha", Palette(Color(0xFFD3A270), Color(0xFF5A3D2B), Color(0xFF100B08), Color(0xFF1A130E), Color(0xFF3F3026))),
    ROYAL("Royal", Palette(Color(0xFFC082FC), Color(0xFF4F2A76), Color(0xFF0D0619), Color(0xFF130B21), Color(0xFF35224F))),
    IVORY("Clean Ivory", Palette(Color(0xFF6C26DB), Color(0xFFD8C9FC), Color(0xFFF4F3FC), Color(0xFFFFFFFF), Color(0xFFDDD6F3), light = true, onAccent = Color.White)),
    SYSTEM("System", Palette(Color(0xFFA688FB), Color(0xFF2A2A44), Color(0xFF0E0E14), Color(0xFF15151D), Color(0xFF2B2B3A)));

    companion object { val DEFAULT = GOLD }
}

/** The palette actually in use (after resolving "System"); read by the page background. */
val LocalPalette = compositionLocalOf { AppTheme.DEFAULT.palette }

enum class CornerChoice(val label: String, val radius: Dp) { SOFT("Soft", 20.dp), CRISP("Crisp", 10.dp) }

enum class TextSizeChoice(val label: String, val factor: Float) {
    SMALL("Small", 0.85f), MEDIUM("Medium", 1.0f), LARGE("Large", 1.18f), HUGE("Huge", 1.35f)
}

data class UiPrefs(
    val theme: AppTheme = AppTheme.DEFAULT,
    val corners: CornerChoice = CornerChoice.SOFT,
    val textSize: TextSizeChoice = TextSizeChoice.MEDIUM,
    val reduceMotion: Boolean = false,
)

/** Holds the current [UiPrefs] as Compose state and persists every change. */
class UiPrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("stsmn_ui", Context.MODE_PRIVATE)
    var current by mutableStateOf(load())
        private set

    fun update(transform: (UiPrefs) -> UiPrefs) {
        val n = transform(current)
        current = n
        runCatching {
            prefs.edit()
                .putString("theme", n.theme.name).putString("corners", n.corners.name)
                .putString("textSize", n.textSize.name).putBoolean("reduceMotion", n.reduceMotion).apply()
        }
    }

    private fun load(): UiPrefs {
        val d = UiPrefs()
        return runCatching {
            UiPrefs(
                theme = enumOr(prefs.getString("theme", null), d.theme),
                corners = enumOr(prefs.getString("corners", null), d.corners),
                textSize = enumOr(prefs.getString("textSize", null), d.textSize),
                reduceMotion = prefs.getBoolean("reduceMotion", false),
            )
        }.getOrDefault(d)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

val LocalUiPrefsStore = compositionLocalOf<UiPrefsStore?> { null }
val LocalCardRadius = compositionLocalOf { 20.dp }
val LocalReduceMotion = compositionLocalOf { false }

/** True when the user asked for reduced motion in the app OR turned animations off in Android's developer/accessibility settings. */
fun systemAnimationsOff(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)


/** Typography families. System fonts for now; swap these three to bundled Manrope / Space Grotesk / JetBrains Mono later. */
object AurelionType {
    val heading: FontFamily = FontFamily.SansSerif
    val number: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.2).sp)
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp)
}

/** Loading skeleton shimmer. Static (no animation) when motion is reduced. */
@Composable
fun Modifier.shimmer(): Modifier {
    val base = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    if (LocalReduceMotion.current) return this.background(base.copy(alpha = 0.6f))
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f, targetValue = 2f, animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    val hi = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    return this.background(Brush.horizontalGradient(listOf(base.copy(alpha = 0.6f), hi, base.copy(alpha = 0.6f)), startX = x * 400f, endX = x * 400f + 400f))
}
