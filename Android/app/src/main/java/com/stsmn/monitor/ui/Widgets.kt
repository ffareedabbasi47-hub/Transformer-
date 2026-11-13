package com.stsmn.monitor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlinx.coroutines.delay

@Composable
fun rememberElapsedNow(periodMs: Long = 1000L): State<Long> = produceState(initialValue = SystemClock.elapsedRealtime()) {
    while (true) { value = SystemClock.elapsedRealtime(); delay(periodMs) }
}

@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(LocalCardRadius.current),
    tint: Color = MaterialTheme.colorScheme.onSurface,
    strength: Float = 0.07f,
): Modifier {
    val cs = MaterialTheme.colorScheme
    val tinted = strength > 0.1f
    val fill = if (tinted) tint.copy(alpha = 0.10f).compositeOver(cs.surface) else cs.surface

    val dark = cs.background.luminance() < 0.5f
    val accentShadow = cs.primary.copy(alpha = if (dark) 0.07f else 0.05f)
    val sheen = Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.55f), Color.White.copy(alpha = if (dark) 0.02f else 0.10f)),
        start = Offset(0f, 0f), end = Offset(600f, 900f),
    )
    val edge = Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (dark) 0.38f else 0.9f), (if (tinted) tint else cs.outline).copy(alpha = 0.25f), Color.White.copy(alpha = if (dark) 0.14f else 0.5f)),
        start = Offset(0f, 0f), end = Offset(500f, 900f),
    )
    val glow = Color.White.copy(alpha = if (dark) 0.10f else 0.5f)
    return this.clip(shape).background(fill.copy(alpha = 0.66f), shape).background(sheen, shape)
        .drawBehind {
            drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 0.12f, 0f), radius = size.width * 0.85f))
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (dark) 0.55f else 0.95f), Color.Transparent)),
                Offset(size.width * 0.08f, 1f), Offset(size.width * 0.92f, 1f), 1.5.dp.toPx(),
            )
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, accentShadow), startY = size.height * 0.6f, endY = size.height))
        }
        .border(1.dp, edge, shape)
}

@Composable
fun Modifier.panel(radius: androidx.compose.ui.unit.Dp = 16.dp, alpha: Float = 0.90f): Modifier {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius)
    val dark = cs.background.luminance() < 0.5f
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.14f else 0.6f), Color.White.copy(alpha = if (dark) 0.03f else 0.12f)))
    val edge = Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (dark) 0.45f else 0.95f), cs.outline.copy(alpha = 0.35f), Color.White.copy(alpha = if (dark) 0.18f else 0.55f)),
        start = Offset(0f, 0f), end = Offset(900f, 200f),
    )
    return this.clip(shape).background(cs.surface.copy(alpha = alpha * 0.7f), shape).background(sheen, shape).border(1.dp, edge, shape)
}

val LocalBottomInset = compositionLocalOf { 0.dp }

@Composable
fun GlassBackground(content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val tState = if (reduce) androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.25f) } as State<Float>
    else androidx.compose.animation.core.rememberInfiniteTransition(label = "aurora").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(36000, easing = androidx.compose.animation.core.LinearEasing)), label = "t",
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to pal.gradTop, 0.55f to pal.base, 1f to pal.base),
        ).drawBehind {
            val t = tState.value
            val tau = 6.2831855f
            fun orb(c: Color, cx: Float, cy: Float, r: Float, a: Float) =
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
            val w = size.width; val h = size.height
            orb(pal.accent, w * (0.80f + 0.12f * kotlin.math.sin(tau * t)), h * (0.20f + 0.06f * kotlin.math.cos(tau * t)), w * 0.75f, 0.20f)
            orb(pal.gradTop, w * (0.12f + 0.10f * kotlin.math.cos(tau * (t + 0.3f))), h * (0.55f + 0.08f * kotlin.math.sin(tau * (t + 0.3f))), w * 0.85f, 0.38f)
            orb(pal.accent, w * (0.65f + 0.15f * kotlin.math.sin(tau * (t + 0.6f))), h * (0.88f + 0.05f * kotlin.math.cos(tau * (t + 0.6f))), w * 0.65f, 0.14f)
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), startY = 0f, endY = h * 0.25f))
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), center = Offset(w / 2f, h / 2f), radius = h * 0.85f))
        },
    ) { content() }
}

@Composable
fun RadarPulse(color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val tState = if (reduce) androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.4f) } as State<Float>
    else androidx.compose.animation.core.rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing)), label = "t",
    )
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension / 2f
        for (k in 0..2) {
            val ph = (tState.value + k / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - ph) * 0.55f), radius = maxR * (0.25f + 0.75f * ph), center = c, style = Stroke(2.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center = c, radius = maxR * 0.4f), radius = maxR * 0.4f, center = c)
        drawCircle(color, radius = maxR * 0.09f, center = c)
    }
}

@Composable
fun GlyphBadge(glyph: String, color: Color, size: Int = 38) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = (size * 0.5f).sp) }
}

@Composable
fun MetricTile(glyph: String, label: String, value: String, accent: Color, dimmed: Boolean, modifier: Modifier = Modifier, sub: String? = null) {
    Box(modifier.glass(RoundedCornerShape(LocalCardRadius.current - 2.dp))) {
        Row(
            Modifier.padding(12.dp).alpha(if (dimmed) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphBadge(glyph, accent)
            Column {
                Text(label, fontSize = 11.sp, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (value == "—") Box(Modifier.padding(top = 4.dp).size(width = 52.dp, height = 18.dp).clip(RoundedCornerShape(6.dp)).shimmer())
                else Text(value, style = AurelionType.number, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = accent, maxLines = 1)
                if (sub != null) Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StateToggle(label: String, on: Boolean?, onColor: Color, offColor: Color, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val color = when (on) { true -> onColor; false -> offColor; null -> StsmnColors.Stale }
    Box(modifier.glass(RoundedCornerShape(LocalCardRadius.current - 2.dp))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).alpha(if (dimmed) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(label, fontSize = 11.sp, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when (on) { true -> "ON"; false -> "OFF"; null -> "—" }, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = color)
            }
            Box(Modifier.size(width = 48.dp, height = 26.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.28f)).padding(3.dp)) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(color)
                        .align(if (on == true) Alignment.CenterEnd else Alignment.CenterStart),
                )
            }
        }
    }
}

@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier, fixedMin: Double? = null, fixedMax: Double? = null) {
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val pts = values.filterNotNull()
        if (values.size < 2 || pts.size < 2) {
            drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
            return@Canvas
        }
        var lo = fixedMin ?: pts.min(); var hi = fixedMax ?: pts.max()
        if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
        val dx = size.width / (values.size - 1)
        val path = Path()
        var pen = false
        values.forEachIndexed { i, v ->
            if (v == null) { pen = false; return@forEachIndexed }
            val x = i * dx
            val y = (size.height - ((v - lo) / (hi - lo)).toFloat() * (size.height - 6.dp.toPx()) - 3.dp.toPx())
            if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

fun heroGradient(c: Color): List<Color> = listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.55f))

@Composable
fun BigBanner(text: String, color: Color, glyph: String = "⚠") {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Text(glyph, fontSize = 18.sp, color = color)
        Text(text, fontWeight = FontWeight.ExtraBold, color = color, letterSpacing = 1.sp, fontSize = 15.sp)
    }
}

@Composable
fun TransformerArt(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 92.dp, height = 84.dp)) {
        val w = size.width
        val h = size.height
        val line = 3.dp.toPx()
        listOf(0.25f, 0.5f, 0.75f).forEach { fx ->
            val x = w * fx
            drawLine(tint, Offset(x, h * 0.08f), Offset(x, h * 0.34f), line, StrokeCap.Round)
            drawCircle(tint, 3.5.dp.toPx(), Offset(x, h * 0.07f))
        }
        val topLeft = Offset(w * 0.06f, h * 0.34f)
        val body = Size(w * 0.88f, h * 0.6f)
        drawRoundRect(tint.copy(alpha = 0.28f), topLeft, body, CornerRadius(8.dp.toPx()))
        drawRoundRect(tint, topLeft, body, CornerRadius(8.dp.toPx()), style = Stroke(width = line))
        for (i in 1..5) {
            val x = w * 0.06f + body.width * i / 6f
            drawLine(tint.copy(alpha = 0.7f), Offset(x, h * 0.42f), Offset(x, h * 0.86f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
