package com.androidex.starfront.gfx

/**
 * A small platform-neutral 2D drawing API: the subset of android.graphics the game uses, with
 * the same names and semantics, so the renderer runs unchanged on Android (over
 * android.graphics.Canvas) and iOS (over Skia). All text is drawn in a bold monospace font.
 */
interface Canvas {
    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint)
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint)
    fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint)
    fun drawOval(l: Float, t: Float, r: Float, b: Float, p: Paint)
    /** An arc of the oval in [oval], [start] and [sweep] in degrees clockwise from 3 o'clock. */
    fun drawArc(oval: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint)
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint)
    fun drawPath(path: Path, p: Paint)
    /** Text with its baseline at [y], placed horizontally by [Paint.textAlign]. */
    fun drawText(s: String, x: Float, y: Float, p: Paint)
    fun save()
    fun restore()
    fun rotate(degrees: Float, px: Float, py: Float)
    /** Like save(), but what's drawn until the matching restore() is composited at [alpha] (0..255). */
    fun saveLayerAlpha(alpha: Int)

    fun drawRect(r: RectF, p: Paint) = drawRect(r.left, r.top, r.right, r.bottom, p)
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) = drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, p)
    fun drawOval(r: RectF, p: Paint) = drawOval(r.left, r.top, r.right, r.bottom, p)
}

/** Measures text in the game's font; each platform installs its own at startup. */
object TextMetrics {
    var measure: (text: String, size: Float) -> Float = { text, size -> text.length * size * 0.6f }
}

object Color {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
}

class Paint {
    enum class Style { FILL, STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }

    /** ARGB. */
    var color: Int = Color.BLACK
    /** The alpha byte of [color]; setting it keeps the RGB. */
    var alpha: Int
        get() = color ushr 24
        set(v) { color = (color and 0xFFFFFF) or (v.coerceIn(0, 255) shl 24) }
    var style = Style.FILL
    var strokeWidth = 0f
    var strokeCap = Cap.BUTT
    var strokeJoin = Join.MITER
    var isAntiAlias = true
    var textSize = 12f
    var textAlign = Align.LEFT
    var pathEffect: DashPathEffect? = null
    var shader: LinearGradient? = null

    fun measureText(s: String): Float = TextMetrics.measure(s, textSize)
}

/** Dashes for stroked shapes: [intervals] alternate on/off lengths, shifted by [phase]. */
class DashPathEffect(val intervals: FloatArray, val phase: Float)

/** A two-colour linear gradient that clamps beyond its ends. */
class LinearGradient(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color0: Int, val color1: Int)

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)

    fun set(l: Float, t: Float, r: Float, b: Float) {
        left = l; top = t; right = r; bottom = b
    }

    fun setEmpty() = set(0f, 0f, 0f, 0f)
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
}

/** A polyline path recorded as commands; platforms replay it into their own path type. */
class Path {
    private var ops = ByteArray(32)
    private var pts = FloatArray(64)
    private var nOps = 0
    private var nPts = 0

    fun reset() {
        nOps = 0
        nPts = 0
    }

    fun moveTo(x: Float, y: Float) = add(MOVE, x, y)
    fun lineTo(x: Float, y: Float) = add(LINE, x, y)
    fun close() = add(CLOSE, null, null)

    private fun add(op: Byte, x: Float?, y: Float?) {
        if (nOps == ops.size) ops = ops.copyOf(nOps * 2)
        ops[nOps++] = op
        if (x != null && y != null) {
            if (nPts + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
            pts[nPts++] = x
            pts[nPts++] = y
        }
    }

    /** Replays the path: [move] and [line] get a point, [close] closes the current contour. */
    inline fun replay(move: (Float, Float) -> Unit, line: (Float, Float) -> Unit, close: () -> Unit) {
        var p = 0
        for (i in 0 until opCount) {
            when (op(i)) {
                MOVE -> { move(point(p), point(p + 1)); p += 2 }
                LINE -> { line(point(p), point(p + 1)); p += 2 }
                else -> close()
            }
        }
    }

    @PublishedApi internal val opCount get() = nOps
    @PublishedApi internal fun op(i: Int) = ops[i]
    @PublishedApi internal fun point(i: Int) = pts[i]

    @PublishedApi internal companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val CLOSE: Byte = 2
    }
}
