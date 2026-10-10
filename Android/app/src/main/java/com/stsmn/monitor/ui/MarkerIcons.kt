package com.stsmn.monitor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import com.stsmn.monitor.domain.MarkerState
import kotlin.math.ceil
import kotlin.math.max

class MarkerIcon(val drawable: BitmapDrawable, val anchorY: Float)

/** Marker colours: green SAFE, yellow WARNING, red EMERGENCY, grey STALE/OFFLINE. */
fun markerColorInt(state: MarkerState): Int = when (state) {
    MarkerState.SAFE -> 0xFF2ECC71.toInt()
    MarkerState.WARNING -> 0xFFFFD21F.toInt()
    MarkerState.EMERGENCY -> 0xFFE5484D.toInt()
    MarkerState.STALE, MarkerState.OFFLINE -> 0xFF8A94A6.toInt()
}

/**
 * Draws the marker as one bitmap: a dark label pill (transformer name + STATUS TEXT, and
 * "DEMO LOCATION" when the coordinates are not real) above a coloured dot at the exact position.
 */
object MarkerIcons {
    private val cache = HashMap<String, MarkerIcon>()

    fun get(ctx: Context, title: String, status: String, note: String?, color: Int, selected: Boolean = false): MarkerIcon {
        val key = "$title|$status|$note|$color|$selected"
        if (cache.size > 200) cache.clear() // bounded: a big fleet can never grow this without limit
        return cache.getOrPut(key) { render(ctx, title, status, note, color, selected) }
    }

    private fun render(ctx: Context, title: String, status: String, note: String?, color: Int, selected: Boolean): MarkerIcon {
        val d = ctx.resources.displayMetrics.density
        fun paint(sizeDp: Float, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = argb
            textSize = sizeDp * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val lines = mutableListOf(
            title to paint(12f, 0xFFFFFFFF.toInt()),
            status to paint(14f, color),
        )
        if (note != null) lines += note to paint(10f, 0xFFFFC107.toInt())

        val padX = 10f * d
        val padY = 6f * d
        val textW = lines.fold(0f) { acc, l -> max(acc, l.second.measureText(l.first)) }
        val textH = lines.fold(0f) { acc, l -> acc + l.second.textSize * 1.3f }
        val pillW = textW + 2 * padX
        val pillH = textH + 2 * padY
        val dotR = 9f * d
        val gap = 4f * d
        val margin = 3f * d
        val w = ceil(pillW + 2 * margin).toInt()
        val h = ceil(pillH + gap + 2 * dotR + 2 * margin).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xF2101A2E.toInt() }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f * d
        }
        val rect = RectF(margin, margin, margin + pillW, margin + pillH)
        c.drawRoundRect(rect, 10f * d, 10f * d, fill)
        c.drawRoundRect(rect, 10f * d, 10f * d, stroke)

        var y = margin + padY
        for ((text, p) in lines) {
            val x = margin + (pillW - p.measureText(text)) / 2f
            c.drawText(text, x, y + p.textSize, p)
            y += p.textSize * 1.3f
        }

        val cx = w / 2f
        val cy = margin + pillH + gap + dotR
        if (selected) c.drawCircle(cx, cy, dotR + 2.2f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; alpha = 200
        })
        c.drawCircle(cx, cy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        c.drawCircle(cx, cy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
        })
        return MarkerIcon(BitmapDrawable(ctx.resources, bmp), cy / h)
    }
}
