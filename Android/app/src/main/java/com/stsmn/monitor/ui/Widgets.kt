package com.stsmn.monitor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlinx.coroutines.delay

/** Ticks every second with the monotonic clock so "x s ago" labels and ages stay live without any data change. */
@Composable
fun rememberElapsedNow(periodMs: Long = 1000L): State<Long> = produceState(initialValue = SystemClock.elapsedRealtime()) {
    while (true) { value = SystemClock.elapsedRealtime(); delay(periodMs) }
}

/**
 * Glassmorphism without a blur API (works on every Android version): a translucent tinted gradient, a bright
 * top-left edge and a thin light border over the soft glow background from [GlassBackground].
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = MaterialTheme.colorScheme.onSurface,
    strength: Float = 0.07f,
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val s = if (dark) strength else strength * 0.7f
    return this
        .clip(shape)
        .background(Brush.linearGradient(listOf(tint.copy(alpha = s + 0.05f), tint.copy(alpha = s * 0.55f))), shape)
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.30f else 0.9f), Color.White.copy(alpha = if (dark) 0.05f else 0.25f))),
            shape,
        )
}

/** App-wide backdrop: deep navy with two soft colour glows, so the glass cards have something to refract. */
@Composable
fun GlassBackground(content: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF1B63C9).copy(alpha = if (dark) 0.38f else 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.04f), radius = size.width * 0.95f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF00B8D9).copy(alpha = if (dark) 0.22f else 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.98f, size.height * 0.55f), radius = size.width * 0.85f,
                ),
            )
        }
        content()
    }
}

/** Round icon badge used on tiles. [glyph] is an emoji so no icon library is needed. */
@Composable
fun GlyphBadge(glyph: String, color: Color, size: Int = 38) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = (size * 0.5f).sp) }
}

/** Mockup-style glass tile: badge on the left, label + value on the right. */
@Composable
fun MetricTile(glyph: String, label: String, value: String, accent: Color, dimmed: Boolean, modifier: Modifier = Modifier, sub: String? = null) {
    Box(modifier.glass(RoundedCornerShape(18.dp))) {
        Row(
            Modifier.padding(12.dp).alpha(if (dimmed) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphBadge(glyph, accent)
            Column {
                Text(label, fontSize = 11.sp, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = accent, maxLines = 1)
                if (sub != null) Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Read-only ON/OFF indicator that looks like the mockup's toggles (the app can never switch the relay). */
@Composable
fun StateToggle(label: String, on: Boolean?, onColor: Color, offColor: Color, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val color = when (on) { true -> onColor; false -> offColor; null -> StsmnColors.Stale }
    Box(modifier.glass(RoundedCornerShape(18.dp))) {
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

/** Tiny line chart of the last readings. Null values break the line (never drawn as a fake 0). */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val pts = values.filterNotNull()
        if (values.size < 2 || pts.size < 2) {
            drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
            return@Canvas
        }
        var lo = pts.min(); var hi = pts.max()
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

/** Gradient hero background colours for a status (mockup: green / amber / red). */
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

/** Small line-art transformer (tank, cooling fins, three bushings) drawn with Canvas - no image files needed. */
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
