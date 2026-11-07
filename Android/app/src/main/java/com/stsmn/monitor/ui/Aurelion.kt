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
import androidx.compose.ui.graphics.lerp
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

/** Brand accent. Gold is the signature; the others are alternatives chosen in Settings > Appearance. */
enum class AccentChoice(val label: String, val color: Color) {
    GOLD("Champagne gold", Color(0xFFC6A76A)),
    PLATINUM("Platinum", Color(0xFFB8C0C8)),
    EMERALD("Emerald", Color(0xFF4C9B78)),
    AMETHYST("Amethyst", Color(0xFF9C78C9)),
}

/** Background depth: three obsidian / graphite families. [base] is the page, [raised] cards, [high] inputs and chips. */
enum class SurfaceChoice(val label: String, val base: Color, val raised: Color, val high: Color, val outline: Color) {
    OBSIDIAN("Obsidian", Color(0xFF090909), Color(0xFF111315), Color(0xFF191B1E), Color(0xFF25282D)),
    GRAPHITE("Graphite", Color(0xFF111315), Color(0xFF191B1E), Color(0xFF25282D), Color(0xFF30343A)),
    AMOLED("True black", Color(0xFF000000), Color(0xFF0A0B0C), Color(0xFF141517), Color(0xFF1F2124)),
}

enum class CornerChoice(val label: String, val radius: Dp) { SOFT("Soft", 20.dp), CRISP("Crisp", 10.dp) }

enum class TextSizeChoice(val label: String, val factor: Float) {
    COMPACT("Compact", 0.92f), STANDARD("Standard", 1.0f), LARGE("Large", 1.14f)
}

data class UiPrefs(
    val accent: AccentChoice = AccentChoice.GOLD,
    val surface: SurfaceChoice = SurfaceChoice.OBSIDIAN,
    val corners: CornerChoice = CornerChoice.SOFT,
    val textSize: TextSizeChoice = TextSizeChoice.STANDARD,
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
                .putString("accent", n.accent.name).putString("surface", n.surface.name).putString("corners", n.corners.name)
                .putString("textSize", n.textSize.name).putBoolean("reduceMotion", n.reduceMotion).apply()
        }
    }

    private fun load(): UiPrefs {
        val d = UiPrefs()
        return runCatching {
            UiPrefs(
                accent = enumOr(prefs.getString("accent", null), d.accent),
                surface = enumOr(prefs.getString("surface", null), d.surface),
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

/** Accent darkened enough to read on the light (ivory) theme. */
fun accentForLight(c: Color): Color = lerp(c, Color.Black, 0.38f)

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
