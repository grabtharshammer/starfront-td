package com.androidex.starfront

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Immediate-mode UI for the HUD, bottom panel and overlays: widgets are drawn every frame and
 * register a tap target in [buttons] as they go. [d] is the display density.
 */
class UiKit(private val d: Float) {
    class Btn(val rect: RectF, val enabled: Boolean, val action: () -> Unit)

    /** Tap targets laid out during the last frame, in drawing order. */
    val buttons = ArrayList<Btn>()

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); color = Color.WHITE }
    val path = Path()
    val rect = RectF()

    fun beginFrame() = buttons.clear()

    /** The topmost button under ([x], [y]), if any. */
    fun hit(x: Float, y: Float): Btn? = buttons.lastOrNull { it.rect.contains(x, y) }

    /** A rounded toggle-style button in the HUD; [on] highlights it. */
    fun hudButton(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, on: Boolean, action: () -> Unit) {
        val br = RectF(l, t, r, b)
        fill.color = if (on) 0xFF1E3A5F.toInt() else 0xFF111A2B.toInt()
        c.drawRoundRect(br, 8 * d, 8 * d, fill)
        stroke.color = if (on) 0xFF64B5F6.toInt() else 0xFF2E4060.toInt(); stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(br, 8 * d, 8 * d, stroke)
        if (label.isNotEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 13 * d
            text.color = Color.WHITE
            c.drawText(label, br.centerX(), br.centerY() + 5 * d, text)
        }
        buttons.add(Btn(br, true, action))
    }

    /**
     * A panel card with an [accent] border; tappable when [action] is given. It looks dimmed when
     * [dimmed] (by default, when not [enabled]) and gets a bright border when [highlight].
     */
    fun card(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, accent: Int, enabled: Boolean,
        dimmed: Boolean = !enabled, highlight: Boolean = false, action: (() -> Unit)?,
    ) {
        val cr = RectF(l, t, r, b)
        fill.color = if (highlight) 0xFF1A2B45.toInt() else if (!dimmed) 0xFF0F1A2C.toInt() else 0xFF0B111C.toInt()
        c.drawRoundRect(cr, 10 * d, 10 * d, fill)
        stroke.color = accent; stroke.alpha = if (highlight) 255 else if (!dimmed) 220 else 120
        stroke.strokeWidth = (if (highlight) 3f else 1.5f) * d
        c.drawRoundRect(cr, 10 * d, 10 * d, stroke)
        stroke.alpha = 255
        if (action != null) buttons.add(Btn(cr, enabled, action))
    }

    /** Left-aligned text, truncated to end before [maxX]. [size] is in dp. */
    fun cardText(c: Canvas, x: Float, y: Float, s: String, size: Float, color: Int, maxX: Float) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = size * d
        text.color = color
        var str = s
        while (str.length > 1 && x + text.measureText(str) > maxX) str = str.dropLast(1)
        c.drawText(str, x, y, text)
    }

    /** Centred text. [size] is in dp. */
    fun centerText(c: Canvas, x: Float, y: Float, s: String, size: Float, color: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size * d
        text.color = color
        c.drawText(s, x, y, text)
    }

    /** A dimmed full-screen menu below [top] with a title, subtitle and a row of buttons. */
    fun overlay(c: Canvas, top: Float, w: Float, h: Float, title: String, sub: String, buttonsSpec: List<Pair<String, () -> Unit>>) {
        fill.color = 0xC0000000.toInt()
        c.drawRect(0f, top, w, h, fill)
        val cx = w / 2f
        val cy = top + (h - top) / 2f
        centerText(c, cx, cy - 40 * d, title, 28f, 0xFF64FFDA.toInt())
        centerText(c, cx, cy - 10 * d, sub, 13f, 0xFFB0BEC5.toInt())
        val bw = 170 * d
        val total = buttonsSpec.size * bw + (buttonsSpec.size - 1) * 16 * d
        var x = cx - total / 2
        for ((label, action) in buttonsSpec) {
            val r = RectF(x, cy + 14 * d, x + bw, cy + 60 * d)
            fill.color = 0xFF0F1A2C.toInt()
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            stroke.color = 0xFF64FFDA.toInt(); stroke.strokeWidth = 2 * d
            c.drawRoundRect(r, 10 * d, 10 * d, stroke)
            centerText(c, r.centerX(), r.centerY() + 6 * d, label, 15f, Color.WHITE)
            buttons.add(Btn(r, true, action))
            x += bw + 16 * d
        }
    }

    companion object {
        fun fmt(v: Float) = if (v >= 10f) v.toInt().toString() else String.format("%.1f", v)
    }
}
