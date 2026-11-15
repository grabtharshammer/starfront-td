package com.androidex.starfront

import android.graphics.Typeface
import com.androidex.starfront.gfx.Canvas
import com.androidex.starfront.gfx.LinearGradient
import com.androidex.starfront.gfx.Paint
import com.androidex.starfront.gfx.Path
import com.androidex.starfront.gfx.RectF
import android.graphics.Canvas as ACanvas
import android.graphics.DashPathEffect as ADash
import android.graphics.LinearGradient as AGradient
import android.graphics.Paint as APaint
import android.graphics.Path as APath
import android.graphics.RectF as ARectF
import android.graphics.Shader as AShader

/** Draws the game's [Canvas] calls onto an android.graphics.Canvas; set [target] each frame. */
class AndroidCanvas : Canvas {
    lateinit var target: ACanvas

    private val paint = APaint().apply { typeface = FONT }
    private val path = APath()
    private val rect = ARectF()
    private var dashOf: com.androidex.starfront.gfx.DashPathEffect? = null
    private var dash: ADash? = null
    private var gradientOf: LinearGradient? = null
    private var gradient: AGradient? = null

    private fun apply(p: Paint): APaint {
        paint.color = p.color
        paint.isAntiAlias = p.isAntiAlias
        paint.style = if (p.style == Paint.Style.FILL) APaint.Style.FILL else APaint.Style.STROKE
        paint.strokeWidth = p.strokeWidth
        paint.strokeCap = when (p.strokeCap) {
            Paint.Cap.BUTT -> APaint.Cap.BUTT
            Paint.Cap.ROUND -> APaint.Cap.ROUND
            Paint.Cap.SQUARE -> APaint.Cap.SQUARE
        }
        paint.strokeJoin = when (p.strokeJoin) {
            Paint.Join.MITER -> APaint.Join.MITER
            Paint.Join.ROUND -> APaint.Join.ROUND
            Paint.Join.BEVEL -> APaint.Join.BEVEL
        }
        paint.textSize = p.textSize
        paint.textAlign = when (p.textAlign) {
            Paint.Align.LEFT -> APaint.Align.LEFT
            Paint.Align.CENTER -> APaint.Align.CENTER
            Paint.Align.RIGHT -> APaint.Align.RIGHT
        }
        val e = p.pathEffect
        if (e !== dashOf) {
            dashOf = e
            dash = e?.let { ADash(it.intervals, it.phase) }
        }
        paint.pathEffect = dash
        val g = p.shader
        if (g !== gradientOf) {
            gradientOf = g
            gradient = g?.let { AGradient(it.x0, it.y0, it.x1, it.y1, it.color0, it.color1, AShader.TileMode.CLAMP) }
        }
        paint.shader = gradient
        return paint
    }

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) = target.drawRect(l, t, r, b, apply(p))
    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) = target.drawRoundRect(l, t, r, b, rx, ry, apply(p))
    override fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint) = target.drawCircle(cx, cy, radius, apply(p))
    override fun drawOval(l: Float, t: Float, r: Float, b: Float, p: Paint) = target.drawOval(l, t, r, b, apply(p))
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) = target.drawLine(x0, y0, x1, y1, apply(p))
    override fun drawText(s: String, x: Float, y: Float, p: Paint) = target.drawText(s, x, y, apply(p))

    override fun drawArc(oval: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) {
        rect.set(oval.left, oval.top, oval.right, oval.bottom)
        target.drawArc(rect, start, sweep, useCenter, apply(p))
    }

    override fun drawPath(path: Path, p: Paint) {
        val ap = this.path
        ap.reset()
        path.replay({ x, y -> ap.moveTo(x, y) }, { x, y -> ap.lineTo(x, y) }, { ap.close() })
        target.drawPath(ap, apply(p))
    }

    override fun save() {
        target.save()
    }

    override fun restore() = target.restore()
    override fun rotate(degrees: Float, px: Float, py: Float) = target.rotate(degrees, px, py)

    override fun saveLayerAlpha(alpha: Int) {
        target.saveLayerAlpha(null, alpha)
    }

    companion object {
        val FONT: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        /** Text widths in the game font, for [com.androidex.starfront.gfx.TextMetrics]. */
        fun measurer(): (String, Float) -> Float {
            val p = APaint(APaint.ANTI_ALIAS_FLAG).apply { typeface = FONT }
            return { s, size -> p.textSize = size; p.measureText(s) }
        }
    }
}
