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

fun markerColorInt(state: MarkerState): Int = when (state) {
    MarkerState.SAFE -> 0xFF2ECC71.toInt()
    MarkerState.WARNING -> 0xFFFFD21F.toInt()
    MarkerState.EMERGENCY -> 0xFFE5484D.toInt()
    MarkerState.STALE, MarkerState.OFFLINE -> 0xFF8A94A6.toInt()
}

object MarkerIcons {
    private val cache = HashMap<String, MarkerIcon>()

    fun get(ctx: Context, title: String, status: String, note: String?, color: Int, selected: Boolean = false): MarkerIcon {
        val key = "$title|$status|$note|$color|$selected"
        if (cache.size > 200) cache.clear()
        return cache.getOrPut(key) { render(ctx, title, status, note, color, selected) }
    }

    private fun render(ctx: Context, title: String, status: String, note: String?, color: Int, selected: Boolean): MarkerIcon {
        val d = ctx.resources.displayMetrics.density
        fun paint(sizeDp: Float, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = argb
            textSize = sizeDp * d
            typeface = Typeface.DEFAULT_BOLD
        }

        val lines = mutableListOf(title to paint(11f, 0xFFFFFFFF.toInt()))
        if (selected) lines += status to paint(10f, color)
        if (note != null) lines += "DEMO" to paint(9f, 0xFFFFC107.toInt())

        val padX = 7f * d
        val padY = 4f * d
        val textW = lines.fold(0f) { acc, l -> max(acc, l.second.measureText(l.first)) }
        val textH = lines.fold(0f) { acc, l -> acc + l.second.textSize * 1.25f }
        val pillW = textW + 2 * padX
        val pillH = textH + 2 * padY
        val dotR = (if (selected) 9f else 7f) * d
        val gap = 3f * d
        val margin = 4f * d
        val w = ceil(max(pillW, 2 * dotR + 8 * d) + 2 * margin).toInt()
        val h = ceil(pillH + gap + 2 * dotR + 2 * margin).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xE6070C16.toInt() }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (selected) color else 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = (if (selected) 1.6f else 1f) * d
        }
        val left = (w - pillW) / 2f
        val rect = RectF(left, margin, left + pillW, margin + pillH)
        c.drawRoundRect(rect, 8f * d, 8f * d, fill)
        c.drawRoundRect(rect, 8f * d, 8f * d, stroke)

        var y = margin + padY
        for ((text, p) in lines) {
            val x = left + (pillW - p.measureText(text)) / 2f
            c.drawText(text, x, y + p.textSize * 0.95f, p)
            y += p.textSize * 1.25f
        }

        val cx = w / 2f
        val cy = margin + pillH + gap + dotR
        if (selected) {
            c.drawCircle(cx, cy, dotR + 4f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 70 })
            c.drawCircle(cx, cy, dotR + 1.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.4f * d; alpha = 220
            })
        }
        c.drawCircle(cx, cy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        c.drawCircle(cx, cy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.8f * d
        })
        return MarkerIcon(BitmapDrawable(ctx.resources, bmp), cy / h)
    }
}
