package com.androidex.starfront.ios

import com.androidex.starfront.gfx.Canvas
import com.androidex.starfront.gfx.DashPathEffect
import com.androidex.starfront.gfx.LinearGradient
import com.androidex.starfront.gfx.Paint
import com.androidex.starfront.gfx.Path
import com.androidex.starfront.gfx.RectF
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.Canvas as SCanvas
import org.jetbrains.skia.Paint as SPaint
import org.jetbrains.skia.Path as SPath

/** Draws the game's [Canvas] calls onto a Skia canvas; set [target] each frame. */
class SkiaCanvas : Canvas {
    lateinit var target: SCanvas

    private val paint = SPaint()
    private val path = SPath()
    private val font = Font(TYPEFACE, 12f)
    private var dashOf: DashPathEffect? = null
    private var dash: PathEffect? = null
    private var gradientOf: LinearGradient? = null
    private var gradient: Shader? = null
    private val layerPaint = SPaint()

    private fun apply(p: Paint): SPaint {
        paint.color = p.color
        paint.isAntiAlias = p.isAntiAlias
        paint.mode = if (p.style == Paint.Style.FILL) PaintMode.FILL else PaintMode.STROKE
        paint.strokeWidth = p.strokeWidth
        paint.strokeCap = when (p.strokeCap) {
            Paint.Cap.BUTT -> PaintStrokeCap.BUTT
            Paint.Cap.ROUND -> PaintStrokeCap.ROUND
            Paint.Cap.SQUARE -> PaintStrokeCap.SQUARE
        }
        paint.strokeJoin = when (p.strokeJoin) {
            Paint.Join.MITER -> PaintStrokeJoin.MITER
            Paint.Join.ROUND -> PaintStrokeJoin.ROUND
            Paint.Join.BEVEL -> PaintStrokeJoin.BEVEL
        }
        val e = p.pathEffect
        if (e !== dashOf) {
            dashOf = e
            dash = e?.let { PathEffect.makeDash(it.intervals, positivePhase(it)) }
        }
        paint.pathEffect = dash
        val g = p.shader
        if (g !== gradientOf) {
            gradientOf = g
            gradient = g?.let { Shader.makeLinearGradient(it.x0, it.y0, it.x1, it.y1, intArrayOf(it.color0, it.color1)) }
        }
        paint.shader = gradient
        return paint
    }

    /** Android accepts any dash phase; normalise it into one dash period for Skia. */
    private fun positivePhase(e: DashPathEffect): Float {
        val period = e.intervals.sum()
        if (period <= 0f) return 0f
        return ((e.phase % period) + period) % period
    }

    // Skia's draw calls return the canvas for chaining; these return Unit.
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) {
        target.drawRect(Rect.makeLTRB(l, t, r, b), apply(p))
    }

    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) {
        target.drawRRect(RRect.makeLTRB(l, t, r, b, rx, ry), apply(p))
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint) {
        target.drawCircle(cx, cy, radius, apply(p))
    }

    override fun drawOval(l: Float, t: Float, r: Float, b: Float, p: Paint) {
        target.drawOval(Rect.makeLTRB(l, t, r, b), apply(p))
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
        target.drawLine(x0, y0, x1, y1, apply(p))
    }

    override fun drawArc(oval: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) {
        target.drawArc(oval.left, oval.top, oval.right, oval.bottom, start, sweep, useCenter, apply(p))
    }

    override fun drawPath(path: Path, p: Paint) {
        val sp = this.path
        sp.reset()
        path.replay({ x, y -> sp.moveTo(x, y) }, { x, y -> sp.lineTo(x, y) }, { sp.closePath() })
        target.drawPath(sp, apply(p))
    }

    override fun drawText(s: String, x: Float, y: Float, p: Paint) {
        font.size = p.textSize
        val left = when (p.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.CENTER -> x - font.measureTextWidth(s) / 2f
            Paint.Align.RIGHT -> x - font.measureTextWidth(s)
        }
        // Text is always filled, whatever the paint's style.
        val sp = apply(p)
        sp.mode = PaintMode.FILL
        target.drawString(s, left, y, font, sp)
    }

    override fun save() {
        target.save()
    }

    override fun restore() {
        target.restore()
    }

    override fun rotate(degrees: Float, px: Float, py: Float) {
        target.translate(px, py)
        target.rotate(degrees)
        target.translate(-px, -py)
    }

    override fun saveLayerAlpha(alpha: Int) {
        layerPaint.color = (alpha.coerceIn(0, 255) shl 24) or 0xFFFFFF
        target.saveLayer(null, layerPaint)
    }

    companion object {
        /** Bold monospace, to match Android's. */
        val TYPEFACE: Typeface? = FontMgr.default.let { fm ->
            fm.matchFamilyStyle("Menlo", FontStyle.BOLD)
                ?: fm.matchFamilyStyle("Courier New", FontStyle.BOLD)
                ?: fm.matchFamilyStyle(null, FontStyle.BOLD)
        }

        /** Text widths in the game font, for [com.androidex.starfront.gfx.TextMetrics]. */
        fun measurer(): (String, Float) -> Float {
            val f = Font(TYPEFACE, 12f)
            return { s, size -> f.size = size; f.measureTextWidth(s) }
        }
    }
}
