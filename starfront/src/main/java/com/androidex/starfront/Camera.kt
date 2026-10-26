package com.androidex.starfront

import com.androidex.starfront.game.ROWS
import com.androidex.starfront.game.SECTION_COLS
import com.androidex.starfront.game.World
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Maps world cells to screen pixels and keeps the view on the playfield. The map area starts
 * [top] px below the top of the screen (under the HUD); [scale] is pixels per cell and [x], [y]
 * the world point at the map area's top-left corner.
 */
class Camera(
    private val top: Float,
    private val world: () -> World,
    /** Height in px currently covered by the bottom panel (0 when closed). */
    private val panelPx: () -> Float,
) {
    var scale = 80f
    private var fitScale = 80f
    var x = 0f
    var y = 0f
    /** World x the camera is gliding toward, if any. */
    var targetX: Float? = null
    private var flingVX = 0f
    private var flingVY = 0f
    private var screenW = 0
    private var screenH = 0

    /** World x range the camera may show: one uncharted sector beyond the frontier, to just past the core. */
    val worldLeft get() = world().minCol - SECTION_COLS.toFloat()
    val worldRight get() = world().coreCol + 2.2f
    val viewW get() = screenW / scale
    val viewH get() = (screenH - top) / scale

    fun sx(wx: Float) = (wx - x) * scale
    fun sy(wy: Float) = top + (wy - y) * scale
    fun colAt(px: Float) = floor(x + px / scale).toInt()
    fun rowAt(py: Float) = floor(y + (py - top) / scale).toInt()

    fun resize(w: Int, h: Int, first: Boolean) {
        screenW = w
        screenH = h
        val oldFit = fitScale
        fitScale = (h - top) / (ROWS + 1.5f)
        scale = if (first) fitScale else scale * fitScale / oldFit
        if (first) reset() else clamp()
    }

    /** Fit the field's height and show the core end. */
    fun reset() {
        scale = fitScale
        x = worldRight - viewW
        y = -0.65f
        clamp()
    }

    fun clamp() {
        if (screenW == 0) return
        val left = worldLeft
        val right = worldRight
        x = if (right - left <= viewW) right - viewW else x.coerceIn(left, right - viewW)
        val t = -0.65f
        val bottom = ROWS + 0.85f + panelPx() / scale
        y = if (bottom - t <= viewH) t - (viewH - (bottom - t)) / 2f else y.coerceIn(t, bottom - viewH)
    }

    /** Scrolls just enough to show a cell above a bottom panel [panelH] px tall. */
    fun ensureVisible(col: Int, row: Int, panelH: Float) {
        clamp()
        val bottomLimit = y + (screenH - top - panelH) / scale
        if (row + 1.2f > bottomLimit) y += row + 1.2f - bottomLimit
        if (col - 0.5f < x) x = col - 0.5f
        if (col + 1.5f > x + viewW) x = col + 1.5f - viewW
        clamp()
    }

    /** Centres on the point a fraction [u] of the way across the scrollable world. */
    fun jumpTo(u: Float) {
        targetX = null
        x = worldLeft + u * (worldRight - worldLeft) - viewW / 2f
        clamp()
    }

    fun pan(dxPx: Float, dyPx: Float) {
        x += dxPx / scale
        y += dyPx / scale
        clamp()
    }

    /** Zooms by [factor] keeping the world point under ([focusX], [focusY]) in place. */
    fun zoom(factor: Float, focusX: Float, focusY: Float) {
        val fx = x + focusX / scale
        val fy = y + (focusY - top) / scale
        scale = (scale * factor).coerceIn(fitScale * 0.5f, fitScale * 2.6f)
        x = fx - focusX / scale
        y = fy - (focusY - top) / scale
        clamp()
    }

    fun fling(vxPx: Float, vyPx: Float) {
        flingVX = -vxPx / scale
        flingVY = -vyPx / scale
    }

    fun stopFling() {
        flingVX = 0f
        flingVY = 0f
    }

    /** Advances the glide toward [targetX] and any fling. */
    fun step(dt: Float) {
        targetX?.let { target ->
            val before = x
            x += (target - x) * min(1f, dt * 3f)
            clamp()
            if (abs(x - target) < 0.02f || abs(x - before) < 0.0005f) targetX = null
        }
        if (abs(flingVX) > 0.05f || abs(flingVY) > 0.05f) {
            x += flingVX * dt
            y += flingVY * dt
            flingVX *= 0.9f
            flingVY *= 0.9f
            clamp()
        }
    }
}
