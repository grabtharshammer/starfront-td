package com.androidex.starfront

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import com.androidex.starfront.audio.Play
import com.androidex.starfront.audio.Spatializer
import com.androidex.starfront.game.AIR_LEVEL
import com.androidex.starfront.game.BARRIER_COLOR
import com.androidex.starfront.game.EFFICIENCY_BONUS
import com.androidex.starfront.game.Enemy
import com.androidex.starfront.game.Sfx
import com.androidex.starfront.game.EnemyType
import com.androidex.starfront.game.FxKind
import com.androidex.starfront.game.Game
import com.androidex.starfront.game.GameEvent
import com.androidex.starfront.game.MAX_LEVEL
import com.androidex.starfront.game.Phase
import com.androidex.starfront.game.ROWS
import com.androidex.starfront.game.Section
import com.androidex.starfront.game.SECTION_COLS
import com.androidex.starfront.game.ShotKind
import com.androidex.starfront.game.TargetMode
import com.androidex.starfront.game.Tower
import com.androidex.starfront.game.TowerType
import com.androidex.starfront.game.WAVES_PER_SECTOR
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the battlefield and HUD on a Canvas and turns touches into game actions.
 * World coordinates are in cells; the camera maps them to pixels at [scale] px per cell.
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private var game: Game,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onSave(state: String?)
        fun onGameOver(wave: Int)
        fun bestWave(): Int
        fun onMuteChanged(muted: Boolean)
    }

    /** Receives the sounds to play each frame; null in headless use. */
    var soundSink: ((Play) -> Unit)? = null
    var muted = false
    private val spatializer = Spatializer()

    private val d = resources.displayMetrics.density
    private val hudH = 54 * d
    private val panelH = 108 * d
    private val pad = 8 * d

    // Safe area from display cutouts (landscape notch on the left or right).
    private var safeL = 0f
    private var safeR = 0f

    // Camera
    private var scale = 80f
    private var fitScale = 80f
    private var camX = 0f
    private var camY = 0f
    private var camTargetX: Float? = null
    private var flingVX = 0f
    private var flingVY = 0f

    // Loop
    var running = false
        set(value) {
            field = value
            lastFrame = System.nanoTime()
            if (value) postInvalidateOnAnimation()
        }
    private var lastFrame = System.nanoTime()
    private var paused = false
    private var speed = 1
    private var autoLaunch = false
    private var autoTimer = 0f

    // Selection: a tower or an empty buildable cell.
    private var selected = false
    private var selCol = 0
    private var selRow = 0

    // Banner messages
    private var banner: String? = null
    private var bannerTime = 0f

    // Buttons laid out during the last draw; taps are tested against them.
    private class Btn(val rect: RectF, val enabled: Boolean, val action: () -> Unit)
    private val buttons = ArrayList<Btn>()
    private val minimap = RectF()

    private enum class Region { MAP, HUD, MINIMAP, PANEL }
    private var touchRegion = Region.MAP

    // ---- Paints ----
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val gridPaint = Paint().apply { color = 0xFF16233A.toInt(); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; color = Color.WHITE }
    private val bg = Paint()
    private val path = Path()
    private val rect = RectF()
    private val rng = Random(1)

    private class Star(val u: Float, val v: Float, val depth: Float, val size: Float, val twinkle: Float)
    private val stars = List(170) { Star(rng.nextFloat(), rng.nextFloat(), 0.05f + rng.nextFloat() * 0.3f, 0.6f + rng.nextFloat() * 1.6f, rng.nextFloat() * 6f) }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flingVX = 0f; flingVY = 0f
            touchRegion = regionAt(e.x, e.y)
            if (touchRegion == Region.MAP) camTargetX = null
            if (touchRegion == Region.MINIMAP) jumpMinimap(e.x)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y); return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            when (touchRegion) {
                Region.MAP -> {
                    camX += dx / scale; camY += dy / scale; clampCamera()
                }
                Region.MINIMAP -> jumpMinimap(e2.x)
                else -> {}
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (touchRegion == Region.MAP) {
                flingVX = -vx / scale; flingVY = -vy / scale
            }
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            if (touchRegion != Region.MAP) return true
            val fx = camX + det.focusX / scale
            val fy = camY + (det.focusY - hudH) / scale
            scale = (scale * det.scaleFactor).coerceIn(fitScale * 0.5f, fitScale * 2.6f)
            camX = fx - det.focusX / scale
            camY = fy - (det.focusY - hudH) / scale
            clampCamera()
            return true
        }
    })

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemGestures())
            safeL = cut.left.toFloat()
            safeR = cut.right.toFloat()
            insets
        }
    }

    // ---- Public API ----

    fun pause() {
        if (game.phase != Phase.GAME_OVER) paused = true
    }

    /** Handles back: closes the panel or menu first. Returns false when there was nothing to close. */
    fun onBack(): Boolean = when {
        selected -> { selected = false; true }
        paused -> { paused = false; true }
        game.phase != Phase.GAME_OVER -> { paused = true; true }
        else -> false
    }

    fun saveState(): String? = game.saveState()

    private fun newGame() {
        game = Game()
        selected = false
        paused = false
        speed = 1
        resetCamera()
        listener.onSave(game.saveState())
        showBanner("NEW COLONY ESTABLISHED")
    }

    // ---- Camera ----

    private val worldLeft get() = game.world.minCol - SECTION_COLS.toFloat()
    private val worldRight get() = game.world.coreCol + 2.2f
    private val viewW get() = width / scale
    private val viewH get() = (height - hudH) / scale

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF04050D.toInt(), 0xFF0D0820.toInt(), Shader.TileMode.CLAMP)
        val oldFit = fitScale
        fitScale = (h - hudH) / (ROWS + 1.5f)
        scale = if (oldw == 0) fitScale else scale * fitScale / oldFit
        if (oldw == 0) resetCamera() else clampCamera()
    }

    private fun resetCamera() {
        scale = fitScale
        camX = worldRight - viewW
        camY = -0.65f
        clampCamera()
    }

    private fun clampCamera() {
        if (width == 0) return
        val left = worldLeft
        val right = worldRight
        camX = if (right - left <= viewW) right - viewW else camX.coerceIn(left, right - viewW)
        val top = -0.65f
        val bottom = ROWS + 0.85f + if (selected) panelH / scale else 0f
        camY = if (bottom - top <= viewH) top - (viewH - (bottom - top)) / 2f else camY.coerceIn(top, bottom - viewH)
    }

    private fun ensureVisible(col: Int, row: Int) {
        clampCamera()
        val bottomLimit = camY + (height - hudH - panelH) / scale
        if (row + 1.2f > bottomLimit) camY += row + 1.2f - bottomLimit
        if (col - 0.5f < camX) camX = col - 0.5f
        if (col + 1.5f > camX + viewW) camX = col + 1.5f - viewW
        clampCamera()
    }

    private fun jumpMinimap(x: Float) {
        val u = ((x - minimap.left) / minimap.width()).coerceIn(0f, 1f)
        camTargetX = null
        camX = worldLeft + u * (worldRight - worldLeft) - viewW / 2f
        clampCamera()
    }

    private fun sx(wx: Float) = (wx - camX) * scale
    private fun sy(wy: Float) = hudH + (wy - camY) * scale

    // ---- Input ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gesture.onTouchEvent(event)
        return true
    }

    private fun regionAt(x: Float, y: Float): Region = when {
        y < hudH && minimap.contains(x, y) -> Region.MINIMAP
        y < hudH -> Region.HUD
        selected && y > height - panelH -> Region.PANEL
        paused || game.phase == Phase.GAME_OVER -> Region.HUD
        else -> Region.MAP
    }

    private fun tap(x: Float, y: Float) {
        for (b in buttons.asReversed()) {
            if (b.rect.contains(x, y)) {
                if (b.enabled) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    b.action()
                } else {
                    game.sound(Sfx.DENIED)
                }
                return
            }
        }
        if (regionAt(x, y) != Region.MAP) return
        val col = floor(camX + x / scale).toInt()
        val row = floor(camY + (y - hudH) / scale).toInt()
        if (selected && col == selCol && row == selRow) {
            selected = false
        } else if (game.towerAt(col, row) != null || game.canBuild(col, row)) {
            selected = true
            selCol = col
            selRow = row
            ensureVisible(col, row)
        } else {
            selected = false
        }
        clampCamera()
    }

    private fun showBanner(msg: String) {
        banner = msg
        bannerTime = 0f
    }

    // ---- Loop ----

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        step(dt)
        buttons.clear()
        drawWorld(canvas)
        drawHud(canvas)
        if (selected && !paused) drawPanel(canvas)
        drawBanner(canvas)
        if (game.phase == Phase.GAME_OVER) drawGameOver(canvas) else if (paused) drawPause(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        if (running && !paused) {
            var remaining = dt * speed
            while (remaining > 0f) {
                val s = min(remaining, 1f / 60f)
                game.update(s)
                remaining -= s
            }
            if (autoLaunch && game.phase == Phase.BUILD) {
                autoTimer += dt
                if (autoTimer > 3f) launch()
            } else {
                autoTimer = 0f
            }
        }
        while (game.events.isNotEmpty()) {
            when (val ev = game.events.removeFirst()) {
                is GameEvent.Message -> showBanner(ev.text)
                GameEvent.Cleared -> listener.onSave(game.saveState())
                is GameEvent.Expanded -> {
                    // Glide west so the player sees the new sector open up.
                    camTargetX = ev.section.startCol - SECTION_COLS * 0.35f
                    listener.onSave(game.saveState())
                }
                is GameEvent.GameOver -> {
                    selected = false
                    listener.onGameOver(ev.wave)
                }
            }
        }
        camTargetX?.let { target ->
            val before = camX
            camX += (target - camX) * min(1f, dt * 3f)
            clampCamera()
            if (abs(camX - target) < 0.02f || abs(camX - before) < 0.0005f) camTargetX = null
        }
        if (abs(flingVX) > 0.05f || abs(flingVY) > 0.05f) {
            camX += flingVX * dt; camY += flingVY * dt
            flingVX *= 0.9f; flingVY *= 0.9f
            clampCamera()
        }
        bannerTime += dt
        playSounds()
    }

    /** Plays this frame's sounds from what's on screen: off-screen sources stay silent. */
    private fun playSounds() {
        if (game.sounds.isEmpty()) return
        val sink = soundSink
        if (sink != null && !muted && width > 0) {
            val plays = spatializer.mix(game.sounds, camX, camY, camX + viewW, camY + viewH, System.nanoTime() / 1e9)
            for (p in plays) sink(p)
        }
        game.sounds.clear()
    }

    private fun launch() {
        if (game.phase != Phase.BUILD) return
        autoTimer = 0f
        game.startWave()
        showBanner("WAVE ${game.wave} INBOUND")
    }

    // ---- World drawing ----

    private fun drawWorld(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val world = game.world
        val time = game.clock
        c.drawRect(0f, 0f, w, h, bg)

        // Parallax stars
        for (s in stars) {
            val x = ((s.u * w * 3f - camX * scale * s.depth) % w + w) % w
            val y = s.v * h - camY * scale * s.depth * 0.5f
            fill.color = Color.WHITE
            fill.alpha = (110 + 90 * sin(time * 1.3f + s.twinkle)).toInt().coerceIn(20, 255)
            c.drawCircle(x, y, s.size * d * 0.6f, fill)
        }
        fill.alpha = 255

        val c0 = max(floor(camX).toInt() - 1, world.minCol - SECTION_COLS)
        val c1 = floor(camX + viewW).toInt() + 1

        // Uncharted sector beyond the frontier
        val gx0 = sx(world.minCol - SECTION_COLS.toFloat())
        val gx1 = sx(world.minCol.toFloat())
        if (gx1 > 0) {
            fill.color = 0x40140A2A
            c.drawRect(gx0, sy(0f), gx1, sy(ROWS.toFloat()), fill)
            stroke.color = 0x664A3A80
            stroke.strokeWidth = 2 * d
            stroke.pathEffect = DashPathEffect(floatArrayOf(10 * d, 8 * d), time * 20 * d)
            c.drawRect(gx0, sy(0f), gx1, sy(ROWS.toFloat()), stroke)
            stroke.pathEffect = null
            text.textAlign = Paint.Align.CENTER
            text.textSize = 15 * d
            text.color = 0xAA8C7CFF.toInt()
            c.drawText("UNCHARTED", (gx0 + gx1) / 2, sy(ROWS / 2f), text)
            text.textSize = 11 * d
            text.color = 0x998C7CFF.toInt()
            c.drawText("clear wave ${game.nextExpansionWave} to expand", (gx0 + gx1) / 2, sy(ROWS / 2f) + 20 * d, text)
        }

        // Sector floors
        for (s in world.sections) {
            if (s.endCol < c0 || s.startCol > c1) continue
            fill.color = if (s.index % 2 == 0) 0xFF0A1424.toInt() else 0xFF0D1122.toInt()
            c.drawRect(sx(s.startCol.toFloat()), sy(0f), sx(s.endCol.toFloat()), sy(ROWS.toFloat()), fill)
            text.textAlign = Paint.Align.LEFT
            text.textSize = 11 * d
            text.color = 0x887FA7D9.toInt()
            c.drawText("SECTOR ${s.index + 1}", sx(s.startCol.toFloat()) + 4 * d, sy(0f) - 6 * d, text)
        }
        // Grid
        val gx = max(c0, world.minCol)
        val gEnd = min(c1, world.coreCol)
        for (col in gx..gEnd) c.drawLine(sx(col.toFloat()), sy(0f), sx(col.toFloat()), sy(ROWS.toFloat()), gridPaint)
        for (row in 0..ROWS) c.drawLine(sx(gx.toFloat()), sy(row.toFloat()), sx(gEnd.toFloat()), sy(row.toFloat()), gridPaint)
        // Sector seams
        stroke.color = 0x553A5F8F
        stroke.strokeWidth = 2 * d
        for (s in world.sections) c.drawLine(sx(s.startCol.toFloat()), sy(-0.3f), sx(s.startCol.toFloat()), sy(ROWS + 0.3f), stroke)

        // Path tiles
        fill.color = 0xFF161034.toInt()
        for (s in world.sections) {
            if (s.endCol < c0 || s.startCol > c1) continue
            for (cell in s.path) c.drawRect(sx(cell.col.toFloat()) + 1, sy(cell.row.toFloat()) + 1, sx(cell.col + 1f) - 1, sy(cell.row + 1f) - 1, fill)
        }
        // Route energy line
        path.reset()
        val wp = world.waypoints
        path.moveTo(sx(wp[0]), sy(wp[1]))
        for (i in 1 until wp.size / 2) path.lineTo(sx(wp[i * 2]), sy(wp[i * 2 + 1]))
        stroke.color = 0x30705CFF
        stroke.strokeWidth = scale * 0.55f
        c.drawPath(path, stroke)
        stroke.color = 0x908C7CFF.toInt()
        stroke.strokeWidth = max(2f, scale * 0.05f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(scale * 0.25f, scale * 0.35f), -time * scale * 1.5f)
        c.drawPath(path, stroke)
        stroke.pathEffect = null

        for (sec in world.sections) if (sec.endCol >= c0 && sec.startCol <= c1) drawPowerBolt(c, sec, time)
        drawGate(c, world.minCol - 1f, world.frontier.entryRow + 0.5f, time)
        drawCore(c, world.coreCol + 0.5f, world.coreRow + 0.5f, time)

        // Selection highlight and range
        if (selected) {
            val t = game.towerAt(selCol, selRow)
            if (t != null) {
                fill.color = t.type.color; fill.alpha = 28
                c.drawCircle(sx(t.x), sy(t.y), t.range * scale, fill)
                fill.alpha = 255
                stroke.color = t.type.color; stroke.alpha = 140; stroke.strokeWidth = 1.5f * d
                c.drawCircle(sx(t.x), sy(t.y), t.range * scale, stroke)
                stroke.alpha = 255
            }
            stroke.color = Color.WHITE
            stroke.strokeWidth = 2 * d
            stroke.alpha = (180 + 70 * sin(time * 6f)).toInt()
            c.drawRect(sx(selCol.toFloat()) + 2, sy(selRow.toFloat()) + 2, sx(selCol + 1f) - 2, sy(selRow + 1f) - 2, stroke)
            stroke.alpha = 255
        }

        val vx0 = camX - 1.5f
        val vx1 = camX + viewW + 1.5f
        for (t in game.towers) if (t.x in vx0..vx1) drawTower(c, t.type, t.level, sx(t.x), sy(t.y), scale, t.angle, time, t.sinceShot)
        for (e in game.enemies) if (!e.type.air && e.x in vx0..vx1) drawEnemy(c, e, time)

        for (p in game.projectiles) {
            if (p.x !in vx0..vx1) continue
            val px = sx(p.x)
            val py = sy(p.y)
            when (p.kind) {
                ShotKind.BOLT -> {
                    val dx = p.tx - p.x
                    val dy = p.ty - p.y
                    val len = max(0.001f, hypot(dx, dy))
                    val tail = 0.28f * scale
                    stroke.color = p.color; stroke.strokeWidth = 0.08f * scale
                    c.drawLine(px, py, px - dx / len * tail, py - dy / len * tail, stroke)
                    stroke.color = Color.WHITE; stroke.strokeWidth = 0.03f * scale
                    c.drawLine(px, py, px - dx / len * tail * 0.6f, py - dy / len * tail * 0.6f, stroke)
                }
                ShotKind.ORB -> {
                    fill.color = p.color; fill.alpha = 70
                    c.drawCircle(px, py, 0.22f * scale, fill)
                    fill.alpha = 255
                    c.drawCircle(px, py, 0.12f * scale, fill)
                    fill.color = Color.WHITE
                    c.drawCircle(px, py, 0.05f * scale, fill)
                }
            }
        }

        // Air units fly above everything on the ground.
        for (e in game.enemies) if (e.type.air && e.x in vx0..vx1) drawEnemy(c, e, time)

        for (f in game.effects) {
            val a = 1f - f.progress
            val fx = sx(f.x)
            val fy = sy(f.y)
            when (f.kind) {
                FxKind.BEAM -> {
                    val p = f.points!!
                    stroke.color = f.color; stroke.alpha = (200 * a).toInt(); stroke.strokeWidth = 0.22f * scale * a + 1
                    c.drawLine(sx(p[0]), sy(p[1]), sx(p[2]), sy(p[3]), stroke)
                    stroke.color = Color.WHITE; stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = 0.06f * scale * a + 1
                    c.drawLine(sx(p[0]), sy(p[1]), sx(p[2]), sy(p[3]), stroke)
                }
                FxKind.ARC -> {
                    val p = f.points!!
                    path.reset()
                    path.moveTo(sx(p[0]), sy(p[1]))
                    for (i in 1 until p.size / 2) {
                        val ax = p[i * 2 - 2]
                        val ay = p[i * 2 - 1]
                        val bx = p[i * 2]
                        val by = p[i * 2 + 1]
                        for (k in 1..4) {
                            val u = k / 4f
                            val j = if (k == 4) 0f else (rng.nextFloat() - 0.5f) * 0.35f
                            path.lineTo(sx(ax + (bx - ax) * u + j), sy(ay + (by - ay) * u - j))
                        }
                    }
                    stroke.color = f.color; stroke.alpha = (160 * a).toInt(); stroke.strokeWidth = 0.14f * scale
                    c.drawPath(path, stroke)
                    stroke.color = Color.WHITE; stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = 0.04f * scale
                    c.drawPath(path, stroke)
                }
                FxKind.RING -> {
                    stroke.color = f.color; stroke.alpha = (220 * a).toInt(); stroke.strokeWidth = 0.06f * scale
                    c.drawCircle(fx, fy, f.size * scale * (0.3f + 0.7f * f.progress), stroke)
                }
                FxKind.BLAST -> {
                    fill.color = f.color; fill.alpha = (150 * a).toInt()
                    c.drawCircle(fx, fy, f.size * scale * (0.4f + 0.6f * f.progress), fill)
                    fill.color = Color.WHITE; fill.alpha = (200 * a * a).toInt()
                    c.drawCircle(fx, fy, f.size * scale * 0.3f, fill)
                }
                FxKind.CORE_HIT -> {
                    stroke.color = 0xFFFF1744.toInt(); stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = 0.1f * scale
                    c.drawCircle(fx, fy, scale * (0.5f + f.progress), stroke)
                }
            }
        }
        stroke.alpha = 255
        for (p in game.particles) {
            fill.color = p.color
            fill.alpha = (255 * (1f - p.t / p.life)).toInt()
            c.drawCircle(sx(p.x), sy(p.y), p.size * scale, fill)
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 12 * d
        for (f in game.floaters) {
            text.color = f.color
            text.alpha = (255 * (1f - f.t / 0.9f)).toInt().coerceIn(0, 255)
            c.drawText(f.text, sx(f.x), sy(f.y), text)
        }
        fill.alpha = 255
        text.alpha = 255

        if (game.wave == 0 && game.towers.size < 2 && !selected) {
            hint(c, "TAP A TILE BESIDE THE PATH TO BUILD  ·  EVERY $WAVES_PER_SECTOR WAVES A NEW SECTOR OPENS WEST")
        } else if (game.wave in 1..2 && game.phase == Phase.BUILD && !selected) {
            hint(c, "SWIPE EAST TO UPGRADE OR SWAP OLDER TOWERS  ·  PINCH TO ZOOM  ·  TAP MINIMAP TO JUMP")
        }
    }

    /**
     * Lightning bolt along the bottom edge of a sector: its length is the power still free
     * there, over a dim track showing the full budget.
     */
    private fun drawPowerBolt(c: Canvas, sec: Section, time: Float) {
        val left = game.powerLeft(sec.index)
        val frac = (left.toFloat() / sec.power).coerceIn(0f, 1f)
        val x0 = sec.startCol + 0.35f
        val full = sec.width - 2.6f
        val y = ROWS + 0.32f
        val col = when {
            frac > 0.5f -> 0xFF40C4FF.toInt()
            frac > 0.2f -> 0xFFFFD740.toInt()
            else -> 0xFFFF5252.toInt()
        }
        stroke.color = 0xFF1A2740.toInt()
        stroke.strokeWidth = 0.14f * scale
        c.drawLine(sx(x0), sy(y), sx(x0 + full), sy(y), stroke)
        if (frac > 0f) {
            val x1 = x0 + full * frac
            val steps = max(2, ((x1 - x0) / 0.3f).toInt())
            path.reset()
            path.moveTo(sx(x0), sy(y))
            for (i in 1..steps) {
                val wx = x0 + (x1 - x0) * i / steps
                val jag = if (i == steps) 0f else (rng.nextFloat() - 0.5f) * 0.28f
                path.lineTo(sx(wx), sy(y + jag))
            }
            stroke.color = col
            stroke.alpha = (90 + 40 * sin(time * 10f)).toInt()
            stroke.strokeWidth = 0.16f * scale
            c.drawPath(path, stroke)
            stroke.alpha = 255
            stroke.strokeWidth = 0.045f * scale
            stroke.color = Color.WHITE
            c.drawPath(path, stroke)
            // Spark at the tip
            fill.color = col
            c.drawCircle(sx(x1), sy(y), scale * (0.07f + 0.03f * sin(time * 17f)), fill)
        }
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11 * d
        text.color = if (left > 0) col else 0xFFFF5252.toInt()
        val label = if (left > 0) "PWR $left/${sec.power}" else "NO POWER"
        c.drawText(label, sx(x0 + full) + 6 * d, sy(y) + 4 * d, text)
        // Second line: the efficiency bonus, earned or how to earn it once the base budget is spent.
        val note = when {
            sec.efficient -> "EFFICIENT"
            left <= 0 -> "MAX ALL +$EFFICIENCY_BONUS"
            else -> null
        }
        if (note != null) {
            text.color = BARRIER_COLOR
            text.textSize = 9 * d
            c.drawText(note, sx(x0 + full) + 6 * d, sy(y) - 9 * d, text)
        }
    }

    private fun hint(c: Canvas, msg: String) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 12 * d
        text.color = 0xCCB0BEC5.toInt()
        val y = hudH + 96 * d
        val tw = text.measureText(msg)
        rect.set(width / 2f - tw / 2 - 12 * d, y - 18 * d, width / 2f + tw / 2 + 12 * d, y + 8 * d)
        fill.color = 0xCC0A0F1C.toInt()
        c.drawRoundRect(rect, 12 * d, 12 * d, fill)
        c.drawText(msg, width / 2f, y, text)
    }

    private fun drawGate(c: Canvas, wx: Float, wy: Float, time: Float) {
        val x = sx(wx)
        val y = sy(wy)
        for (i in 0 until 3) {
            val r = scale * (0.25f + 0.13f * i + 0.04f * sin(time * 3f + i))
            stroke.color = 0xFFD500F9.toInt()
            stroke.alpha = 200 - i * 55
            stroke.strokeWidth = 0.06f * scale
            rect.set(x - r * 0.55f, y - r, x + r * 0.55f, y + r)
            c.drawOval(rect, stroke)
        }
        fill.color = 0xFF2A0040.toInt()
        rect.set(x - scale * 0.12f, y - scale * 0.22f, x + scale * 0.12f, y + scale * 0.22f)
        c.drawOval(rect, fill)
        stroke.alpha = 255
    }

    private fun drawCore(c: Canvas, wx: Float, wy: Float, time: Float) {
        val x = sx(wx)
        val y = sy(wy)
        val health = game.lives / 20f
        val col = if (health > 0.5f) 0xFF18FFFF.toInt() else if (health > 0.25f) 0xFFFFD740.toInt() else 0xFFFF1744.toInt()
        fill.color = col; fill.alpha = 40
        c.drawCircle(x, y, scale * (0.75f + 0.05f * sin(time * 2f)), fill)
        fill.alpha = 255
        fill.color = 0xFF16213A.toInt()
        polygon(x, y, scale * 0.55f, 6, time * 0.2f)
        c.drawPath(path, fill)
        stroke.color = col; stroke.strokeWidth = 0.05f * scale
        c.drawPath(path, stroke)
        for (i in 0 until 3) {
            val r = scale * (0.18f + 0.1f * i)
            rect.set(x - r, y - r, x + r, y + r)
            val start = (time * (60f + 40 * i) * if (i % 2 == 0) 1 else -1) % 360f
            c.drawArc(rect, start, 240f, false, stroke)
        }
        fill.color = Color.WHITE
        c.drawCircle(x, y, scale * 0.08f, fill)
    }

    /** Builds a regular polygon into [path]. */
    private fun polygon(x: Float, y: Float, r: Float, sides: Int, rot: Float) {
        path.reset()
        for (i in 0 until sides) {
            val a = rot + i * 2f * Math.PI.toFloat() / sides
            val px = x + cos(a) * r
            val py = y + sin(a) * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private fun drawTower(c: Canvas, type: TowerType, level: Int, x: Float, y: Float, s: Float, angle: Float, time: Float, sinceShot: Float) {
        val col = type.color
        val half = s * 0.42f
        rect.set(x - half, y - half, x + half, y + half)
        fill.color = 0xFF172237.toInt()
        c.drawRoundRect(rect, s * 0.14f, s * 0.14f, fill)
        stroke.color = 0xFF2E4060.toInt(); stroke.strokeWidth = s * 0.035f
        c.drawRoundRect(rect, s * 0.14f, s * 0.14f, stroke)
        stroke.color = col; stroke.alpha = 120; stroke.strokeWidth = s * 0.04f
        c.drawCircle(x, y, s * 0.3f, stroke)
        stroke.alpha = 255
        val deg = Math.toDegrees(angle.toDouble()).toFloat()
        c.save()
        c.rotate(deg, x, y)
        when (type) {
            TowerType.PULSE -> {
                stroke.color = 0xFFCFD8DC.toInt(); stroke.strokeWidth = s * 0.07f
                c.drawLine(x, y - s * 0.08f, x + s * 0.38f, y - s * 0.08f, stroke)
                c.drawLine(x, y + s * 0.08f, x + s * 0.38f, y + s * 0.08f, stroke)
                fill.color = col
                c.drawCircle(x, y, s * 0.16f, fill)
            }
            TowerType.PLASMA -> {
                stroke.color = 0xFF90A4AE.toInt(); stroke.strokeWidth = s * 0.17f
                stroke.strokeCap = Paint.Cap.BUTT
                c.drawLine(x, y, x + s * 0.33f, y, stroke)
                stroke.strokeCap = Paint.Cap.ROUND
                fill.color = 0xFF5D2E00.toInt()
                polygon(x, y, s * 0.22f, 8, 0f)
                c.drawPath(path, fill)
                fill.color = col
                c.drawCircle(x, y, s * (0.09f + 0.02f * sin(time * 5f)), fill)
            }
            TowerType.CRYO -> {
                c.rotate(time * 40f - deg, x, y)
                stroke.color = col; stroke.strokeWidth = s * 0.05f
                for (i in 0 until 6) {
                    val a = i * Math.PI.toFloat() / 3
                    c.drawLine(x, y, x + cos(a) * s * 0.3f, y + sin(a) * s * 0.3f, stroke)
                }
                fill.color = 0xFFE1F5FE.toInt()
                c.drawCircle(x, y, s * 0.11f, fill)
            }
            TowerType.TESLA -> {
                stroke.color = col; stroke.strokeWidth = s * 0.04f
                c.drawCircle(x, y, s * 0.24f, stroke)
                c.drawCircle(x, y, s * 0.16f, stroke)
                fill.color = if (sinceShot < 0.12f) Color.WHITE else col
                c.drawCircle(x, y, s * (0.09f + 0.025f * sin(time * 8f)), fill)
            }
            TowerType.RAIL -> {
                fill.color = 0xFF263238.toInt()
                rect.set(x - s * 0.2f, y - s * 0.14f, x + s * 0.12f, y + s * 0.14f)
                c.drawRect(rect, fill)
                stroke.color = 0xFFB0BEC5.toInt(); stroke.strokeWidth = s * 0.045f
                c.drawLine(x, y - s * 0.06f, x + s * 0.48f, y - s * 0.06f, stroke)
                c.drawLine(x, y + s * 0.06f, x + s * 0.48f, y + s * 0.06f, stroke)
                fill.color = col
                c.drawCircle(x - s * 0.06f, y, s * 0.07f, fill)
            }
        }
        if (sinceShot < 0.07f && type != TowerType.CRYO && type != TowerType.TESLA) {
            fill.color = Color.WHITE
            c.drawCircle(x + s * 0.42f, y, s * 0.09f, fill)
        }
        c.restore()
        // Level pips
        fill.color = 0xFFFFD740.toInt()
        val pipY = y + s * 0.33f
        val start = x - (level - 1) * s * 0.065f
        for (i in 0 until level) c.drawCircle(start + i * s * 0.13f, pipY, s * 0.04f, fill)
    }

    private fun drawEnemy(c: Canvas, e: Enemy, time: Float) {
        val x = sx(e.x)
        val y = sy(e.y)
        val r = e.radius * scale
        val col = e.type.color
        if (e.type.air) {
            // Ground shadow, offset to suggest altitude.
            fill.color = 0x66000000
            rect.set(x - r * 0.9f + scale * 0.2f, y - r * 0.45f + scale * 0.45f, x + r * 0.9f + scale * 0.2f, y + r * 0.45f + scale * 0.45f)
            c.drawOval(rect, fill)
        }
        c.save()
        c.rotate(Math.toDegrees(e.heading.toDouble()).toFloat(), x, y)
        fill.color = col
        stroke.color = 0xFF000000.toInt(); stroke.alpha = 120; stroke.strokeWidth = r * 0.15f
        when (e.type) {
            EnemyType.SCOUT -> {
                path.reset()
                path.moveTo(x + r, y)
                path.lineTo(x - r * 0.8f, y - r * 0.8f)
                path.lineTo(x - r * 0.4f, y)
                path.lineTo(x - r * 0.8f, y + r * 0.8f)
                path.close()
                c.drawPath(path, fill)
            }
            EnemyType.GRUNT -> {
                val wob = sin(time * 12f + e.dist * 4f) * r * 0.2f
                stroke.color = col; stroke.alpha = 255; stroke.strokeWidth = r * 0.18f
                c.drawLine(x - r * 0.3f, y - r * 0.5f, x - r * 0.3f + wob, y - r * 1.05f, stroke)
                c.drawLine(x + r * 0.3f, y - r * 0.5f, x + r * 0.3f - wob, y - r * 1.05f, stroke)
                c.drawLine(x - r * 0.3f, y + r * 0.5f, x - r * 0.3f - wob, y + r * 1.05f, stroke)
                c.drawLine(x + r * 0.3f, y + r * 0.5f, x + r * 0.3f + wob, y + r * 1.05f, stroke)
                rect.set(x - r * 0.75f, y - r * 0.65f, x + r * 0.75f, y + r * 0.65f)
                c.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
                fill.color = 0xFF3A0000.toInt()
                c.drawCircle(x + r * 0.4f, y, r * 0.18f, fill)
            }
            EnemyType.SWARM -> c.drawCircle(x, y, r, fill)
            EnemyType.BRUTE -> {
                polygon(x, y, r, 6, 0f)
                c.drawPath(path, fill)
                stroke.color = 0xFF4E342E.toInt(); stroke.alpha = 255; stroke.strokeWidth = r * 0.22f
                c.drawPath(path, stroke)
                fill.color = 0xFFFFCCBC.toInt()
                c.drawCircle(x + r * 0.35f, y, r * 0.2f, fill)
            }
            EnemyType.PHASE -> {
                fill.alpha = (150 + 100 * sin(time * 9f + e.dist)).toInt().coerceIn(60, 255)
                path.reset()
                path.moveTo(x + r * 1.1f, y); path.lineTo(x, y - r * 0.8f)
                path.lineTo(x - r * 1.1f, y); path.lineTo(x, y + r * 0.8f); path.close()
                c.drawPath(path, fill)
                fill.alpha = 255
            }
            EnemyType.GUNSHIP -> {
                // Engine glow
                val flick = 0.8f + 0.2f * sin(time * 40f + e.wavePhase)
                fill.color = 0xFF40C4FF.toInt(); fill.alpha = 150
                c.drawCircle(x - r * 1.05f, y - r * 0.32f, r * 0.26f * flick, fill)
                c.drawCircle(x - r * 1.05f, y + r * 0.32f, r * 0.26f * flick, fill)
                fill.color = Color.WHITE; fill.alpha = 230
                c.drawCircle(x - r * 1.0f, y - r * 0.32f, r * 0.11f, fill)
                c.drawCircle(x - r * 1.0f, y + r * 0.32f, r * 0.11f, fill)
                fill.alpha = 255
                // Swept wings
                fill.color = 0xFF455A64.toInt()
                path.reset()
                path.moveTo(x + r * 0.2f, y)
                path.lineTo(x - r * 0.75f, y - r * 1.15f)
                path.lineTo(x - r * 0.95f, y - r * 1.05f)
                path.lineTo(x - r * 0.6f, y)
                path.lineTo(x - r * 0.95f, y + r * 1.05f)
                path.lineTo(x - r * 0.75f, y + r * 1.15f)
                path.close()
                c.drawPath(path, fill)
                stroke.color = col; stroke.alpha = 255; stroke.strokeWidth = r * 0.08f
                c.drawLine(x - r * 0.75f, y - r * 1.15f, x - r * 0.95f, y - r * 1.05f, stroke)
                c.drawLine(x - r * 0.75f, y + r * 1.15f, x - r * 0.95f, y + r * 1.05f, stroke)
                // Hull
                fill.color = 0xFFB0BEC5.toInt()
                path.reset()
                path.moveTo(x + r * 1.25f, y)
                path.lineTo(x + r * 0.2f, y - r * 0.38f)
                path.lineTo(x - r * 1.0f, y - r * 0.45f)
                path.lineTo(x - r * 0.85f, y)
                path.lineTo(x - r * 1.0f, y + r * 0.45f)
                path.lineTo(x + r * 0.2f, y + r * 0.38f)
                path.close()
                c.drawPath(path, fill)
                stroke.color = 0xFF263238.toInt(); stroke.strokeWidth = r * 0.06f
                c.drawPath(path, stroke)
                // Accent stripe and cockpit
                stroke.color = col; stroke.strokeWidth = r * 0.1f
                c.drawLine(x - r * 0.7f, y, x + r * 0.55f, y, stroke)
                fill.color = 0xFF1DE9B6.toInt()
                rect.set(x + r * 0.3f, y - r * 0.17f, x + r * 0.85f, y + r * 0.17f)
                c.drawOval(rect, fill)
                fill.color = Color.WHITE; fill.alpha = 180
                rect.set(x + r * 0.5f, y - r * 0.08f, x + r * 0.72f, y + r * 0.02f)
                c.drawOval(rect, fill)
                fill.alpha = 255
            }
            EnemyType.BOSS -> {
                c.rotate(time * 50f, x, y)
                stroke.color = col; stroke.alpha = 255; stroke.strokeWidth = r * 0.12f
                for (i in 0 until 8) {
                    val a = i * Math.PI.toFloat() / 4
                    c.drawLine(x + cos(a) * r * 0.8f, y + sin(a) * r * 0.8f, x + cos(a) * r * 1.25f, y + sin(a) * r * 1.25f, stroke)
                }
                fill.color = 0xFF3A0048.toInt()
                c.drawCircle(x, y, r, fill)
                stroke.strokeWidth = r * 0.1f
                c.drawCircle(x, y, r * 0.85f, stroke)
                fill.color = 0xFFFF1744.toInt()
                c.drawCircle(x, y, r * (0.3f + 0.05f * sin(time * 6f)), fill)
            }
        }
        c.restore()
        stroke.alpha = 255
        if (e.sinceHit < 0.06f) {
            fill.color = Color.WHITE; fill.alpha = 150
            c.drawCircle(x, y, r * 0.9f, fill)
            fill.alpha = 255
        }
        if (e.slowTimer > 0f) {
            stroke.color = 0xFF80D8FF.toInt(); stroke.alpha = 170; stroke.strokeWidth = r * 0.18f
            c.drawCircle(x, y, r * 1.25f, stroke)
            stroke.alpha = 255
        }
        if (e.shield > 0.5f) {
            stroke.color = 0xFF18FFFF.toInt()
            stroke.alpha = (60 + 160 * e.shield / e.maxShield).toInt()
            stroke.strokeWidth = r * 0.12f
            c.drawCircle(x, y, r * 1.45f, stroke)
            stroke.alpha = 255
        }
        if (e.barrier > 0.5f) {
            // Seam barrier: a gold hexagon that thickens with its strength.
            stroke.color = BARRIER_COLOR
            stroke.alpha = 220
            stroke.strokeWidth = r * (0.08f + 0.12f * (e.barrier / e.maxHp).coerceAtMost(1f))
            polygon(x, y, r * 1.7f, 6, time * 1.5f)
            c.drawPath(path, stroke)
            stroke.alpha = 255
        }
        if (e.hp < e.maxHp || e.shield < e.maxShield || e.barrier < e.maxBarrier) {
            val bw = if (e.type == EnemyType.BOSS) scale * 1.1f else scale * 0.55f
            val by = y - r - scale * 0.2f
            fill.color = 0xCC000000.toInt()
            c.drawRect(x - bw / 2, by, x + bw / 2, by + 4 * d, fill)
            val f = (e.hp / e.maxHp).coerceIn(0f, 1f)
            fill.color = if (f > 0.5f) 0xFF69F0AE.toInt() else if (f > 0.25f) 0xFFFFD740.toInt() else 0xFFFF5252.toInt()
            c.drawRect(x - bw / 2, by, x - bw / 2 + bw * f, by + 4 * d, fill)
            if (e.maxShield > 0f) {
                fill.color = 0xFF18FFFF.toInt()
                c.drawRect(x - bw / 2, by - 3 * d, x - bw / 2 + bw * (e.shield / e.maxShield), by - 1 * d, fill)
            }
            if (e.barrier > 0f) {
                fill.color = BARRIER_COLOR
                c.drawRect(x - bw / 2, by + 5 * d, x - bw / 2 + bw * (e.barrier / e.maxBarrier), by + 7 * d, fill)
            }
        }
    }

    // ---- HUD ----

    private fun drawHud(c: Canvas) {
        val w = width.toFloat()
        fill.color = 0xE6070B16.toInt()
        c.drawRect(0f, 0f, w, hudH, fill)
        stroke.color = 0xFF1E3A5F.toInt(); stroke.strokeWidth = 1.5f * d
        c.drawLine(0f, hudH, w, hudH, stroke)

        val cy = hudH / 2
        var x = safeL + 12 * d
        text.textAlign = Paint.Align.LEFT
        text.textSize = 17 * d

        // Credits
        fill.color = 0xFFFFD740.toInt()
        polygon(x + 7 * d, cy, 7 * d, 4, 0f)
        c.drawPath(path, fill)
        text.color = 0xFFFFD740.toInt()
        c.drawText("${game.credits}", x + 20 * d, cy + 6 * d, text)
        x += 20 * d + text.measureText("00000") + 8 * d

        // Core integrity
        stroke.color = 0xFF18FFFF.toInt(); stroke.strokeWidth = 2.5f * d
        c.drawCircle(x + 7 * d, cy, 6 * d, stroke)
        text.color = if (game.lives > 5) 0xFF18FFFF.toInt() else 0xFFFF5252.toInt()
        c.drawText("${game.lives}", x + 20 * d, cy + 6 * d, text)
        x += 20 * d + text.measureText("000") + 8 * d

        text.color = Color.WHITE
        text.textSize = 13 * d
        c.drawText("WAVE", x, cy - 4 * d, text)
        text.textSize = 17 * d
        c.drawText("${max(game.wave, 1)}", x, cy + 14 * d, text)
        text.textSize = 10 * d
        text.color = 0xFF7FA7D9.toInt()
        c.drawText("BEST ${listener.bestWave()}", x + 48 * d, cy + 14 * d, text)
        c.drawText("${game.world.sections.size} SECT", x + 48 * d, cy - 4 * d, text)
        x += 48 * d + text.measureText("0000 SECT") + 12 * d

        // Right-side buttons, laid out from the right edge.
        var rx = w - safeR - 8 * d
        val bh = hudH - 14 * d
        val by = 7 * d

        val launchW = 136 * d
        rect.set(rx - launchW, by, rx, by + bh)
        if (game.phase == Phase.BUILD) {
            val r = RectF(rect)
            fill.color = 0xFF00695C.toInt()
            c.drawRoundRect(r, 8 * d, 8 * d, fill)
            stroke.color = 0xFF64FFDA.toInt(); stroke.strokeWidth = 1.5f * d
            c.drawRoundRect(r, 8 * d, 8 * d, stroke)
            fill.color = Color.WHITE
            path.reset()
            path.moveTo(r.left + 14 * d, r.centerY() - 7 * d)
            path.lineTo(r.left + 26 * d, r.centerY())
            path.lineTo(r.left + 14 * d, r.centerY() + 7 * d)
            path.close()
            c.drawPath(path, fill)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 14 * d
            text.color = Color.WHITE
            c.drawText("WAVE ${game.wave + 1}", r.centerX() + 10 * d, r.centerY() + 5 * d, text)
            buttons.add(Btn(r, true) { launch() })
        } else if (game.phase == Phase.WAVE) {
            fill.color = 0xFF2A1020.toInt()
            c.drawRoundRect(rect, 8 * d, 8 * d, fill)
            val f = 1f - game.enemiesRemaining / max(1f, game.waveSize.toFloat())
            fill.color = 0xFF8E2442.toInt()
            c.drawRoundRect(rect.left, rect.top, rect.left + rect.width() * f, rect.bottom, 8 * d, 8 * d, fill)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 12 * d
            text.color = 0xFFFF8A80.toInt()
            c.drawText("HOSTILES ${game.enemiesRemaining}", rect.centerX(), rect.centerY() + 4 * d, text)
        }
        rx -= launchW + 6 * d

        // Sound toggle
        val sndW = 52 * d
        hudButton(c, rx - sndW, by, rx, by + bh, if (muted) "OFF" else "SFX", !muted) {
            muted = !muted
            listener.onMuteChanged(muted)
        }
        rx -= sndW + 6 * d

        // Auto-launch toggle
        val autoW = 52 * d
        hudButton(c, rx - autoW, by, rx, by + bh, "AUTO", autoLaunch) { autoLaunch = !autoLaunch }
        rx -= autoW + 6 * d
        val speedW = 48 * d
        hudButton(c, rx - speedW, by, rx, by + bh, "x$speed", speed > 1) { speed = if (speed >= 3) 1 else speed + 1 }
        rx -= speedW + 6 * d
        val pauseW = 44 * d
        hudButton(c, rx - pauseW, by, rx, by + bh, "", paused) { paused = !paused }
        fill.color = Color.WHITE
        val pcx = rx - pauseW / 2
        c.drawRect(pcx - 6 * d, cy - 8 * d, pcx - 2 * d, cy + 8 * d, fill)
        c.drawRect(pcx + 2 * d, cy - 8 * d, pcx + 6 * d, cy + 8 * d, fill)
        rx -= pauseW + 12 * d

        // Minimap fills the gap.
        minimap.set(x, 8 * d, rx, hudH - 8 * d)
        if (minimap.width() > 60 * d) drawMinimap(c) else minimap.setEmpty()
    }

    private fun hudButton(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, on: Boolean, action: () -> Unit) {
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

    private fun drawMinimap(c: Canvas) {
        val m = minimap
        val left = worldLeft
        val span = worldRight - left
        fun mx(wx: Float) = m.left + (wx - left) / span * m.width()
        fun my(wy: Float) = m.top + wy / ROWS * m.height()
        fill.color = 0xFF050810.toInt()
        c.drawRoundRect(m, 4 * d, 4 * d, fill)
        val world = game.world
        fill.color = 0x40140A2A
        c.drawRect(mx(world.minCol - SECTION_COLS.toFloat()), m.top, mx(world.minCol.toFloat()), m.bottom, fill)
        for (s in world.sections) {
            fill.color = if (s.index % 2 == 0) 0xFF102038.toInt() else 0xFF141A30.toInt()
            c.drawRect(mx(s.startCol.toFloat()), m.top, mx(s.endCol.toFloat()), m.bottom, fill)
        }
        path.reset()
        val wp = world.waypoints
        path.moveTo(mx(wp[0]), my(wp[1]))
        for (i in 1 until wp.size / 2) path.lineTo(mx(wp[i * 2]), my(wp[i * 2 + 1]))
        stroke.color = 0xFF6A5ACD.toInt(); stroke.strokeWidth = 1.5f * d
        c.drawPath(path, stroke)
        for (t in game.towers) {
            fill.color = t.type.color
            c.drawCircle(mx(t.x), my(t.y), 1.6f * d, fill)
        }
        fill.color = 0xFFFF5252.toInt()
        for (e in game.enemies) c.drawCircle(mx(e.x), my(e.y), 1.4f * d, fill)
        fill.color = 0xFF18FFFF.toInt()
        c.drawCircle(mx(world.coreCol + 0.5f), my(world.coreRow + 0.5f), 2.5f * d, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 1.2f * d
        c.drawRect(mx(camX).coerceAtLeast(m.left), m.top, mx(camX + viewW).coerceAtMost(m.right), m.bottom, stroke)
    }

    // ---- Bottom panel ----

    private fun drawPanel(c: Canvas) {
        val w = width.toFloat()
        val top = height - panelH
        fill.color = 0xF0070B16.toInt()
        c.drawRect(0f, top, w, height.toFloat(), fill)
        stroke.color = 0xFF1E3A5F.toInt(); stroke.strokeWidth = 1.5f * d
        c.drawLine(0f, top, w, top, stroke)
        val tower = game.towerAt(selCol, selRow)
        if (tower == null && !game.canBuild(selCol, selRow)) {
            selected = false
            return
        }
        val l = safeL + pad
        val r = w - safeR - pad
        val ct = top + pad
        val cb = height - pad
        if (tower == null) {
            val types = TowerType.entries
            val cw = min(190 * d, (r - l - pad * (types.size - 1)) / types.size)
            var x = l
            for (type in types) {
                val powered = game.hasPowerFor(type, selCol)
                val ok = game.credits >= type.cost && powered
                card(c, x, ct, x + cw, cb, if (ok) type.color else 0xFF37474F.toInt(), ok) {
                    if (game.build(type, selCol, selRow) != null) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                drawTower(c, type, 1, x + 28 * d, ct + 30 * d, 44 * d, -0.6f, game.clock, 10f)
                cardText(c, x + 58 * d, ct + 22 * d, type.title.uppercase(), 11f, Color.WHITE, x + cw - 6 * d)
                cardText(c, x + 58 * d, ct + 40 * d, type.blurb, 10f, 0xFF90A4AE.toInt(), x + cw - 6 * d)
                cardText(c, x + 10 * d, cb - 12 * d, "${type.cost}", 15f, if (game.credits >= type.cost) 0xFFFFD740.toInt() else 0xFFFF5252.toInt(), x + cw)
                cardText(c, x + cw * 0.42f, cb - 12 * d, "R${fmt(type.range)}", 10f, 0xFF7FA7D9.toInt(), x + cw)
                cardText(c, x + cw * 0.7f, cb - 12 * d, "P${type.power}", 10f, if (powered) 0xFF40C4FF.toInt() else 0xFFFF5252.toInt(), x + cw)
                x += cw + pad
            }
            return
        }

        // Tower selected: info, upgrade, target, sell, then swap options.
        val others = TowerType.entries.filter { it != tower.type }
        val units = 2.4f + 1.25f + 1f + 1f + others.size
        val gap = pad * 0.75f
        val unit = min(120 * d, (r - l - gap * (3 + others.size)) / units)
        var x = l

        val iw = unit * 2.4f
        card(c, x, ct, x + iw, cb, tower.type.color, true, null)
        drawTower(c, tower.type, tower.level, x + 30 * d, ct + 32 * d, 46 * d, tower.angle, game.clock, 10f)
        cardText(c, x + 60 * d, ct + 20 * d, "${tower.type.title.uppercase()}", 12f, Color.WHITE, x + iw - 4 * d)
        cardText(c, x + 60 * d, ct + 37 * d, "LV ${tower.level}/$MAX_LEVEL · ${tower.kills} KILLS", 10f, 0xFFFFD740.toInt(), x + iw - 4 * d)
        cardText(c, x + 10 * d, cb - 26 * d, "DMG ${fmt(tower.damage)}  RNG ${fmt(tower.range)}", 10f, 0xFFB0BEC5.toInt(), x + iw - 4 * d)
        cardText(c, x + 10 * d, cb - 10 * d, "RATE ${fmt(tower.rate)}/s  ${extra(tower)}", 10f, 0xFFB0BEC5.toInt(), x + iw - 4 * d)
        x += iw + gap

        val uw = unit * 1.25f
        val up = tower.upgradeCost
        val canUp = up != null && game.credits >= up
        card(c, x, ct, x + uw, cb, if (canUp) 0xFF69F0AE.toInt() else 0xFF37474F.toInt(), canUp) {
            if (game.upgrade(tower)) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        centerText(c, x + uw / 2, ct + 24 * d, "UPGRADE", 12f, Color.WHITE)
        if (up == null) {
            centerText(c, x + uw / 2, ct + 52 * d, "MAX", 15f, 0xFF69F0AE.toInt())
        } else {
            centerText(c, x + uw / 2, ct + 50 * d, "$up", 16f, if (canUp) 0xFFFFD740.toInt() else 0xFFFF5252.toInt())
            centerText(c, x + uw / 2, ct + 70 * d, "DMG→${fmt(tower.damage * 1.55f)}", 9f, 0xFF90A4AE.toInt())
        }
        x += uw + gap

        card(c, x, ct, x + unit, cb, 0xFF64B5F6.toInt(), true) {
            tower.mode = TargetMode.entries[(tower.mode.ordinal + 1) % TargetMode.entries.size]
        }
        centerText(c, x + unit / 2, ct + 24 * d, "TARGET", 11f, Color.WHITE)
        centerText(c, x + unit / 2, ct + 52 * d, tower.mode.label, 12f, 0xFF64B5F6.toInt())
        x += unit + gap

        card(c, x, ct, x + unit, cb, 0xFFFF8A80.toInt(), true) {
            game.sell(tower)
            selected = false
        }
        centerText(c, x + unit / 2, ct + 24 * d, "SELL", 11f, Color.WHITE)
        centerText(c, x + unit / 2, ct + 52 * d, "+${tower.sellValue}", 14f, 0xFFFFD740.toInt())
        x += unit + gap

        for (type in others) {
            val cost = game.replaceCost(tower, type)
            val powered = game.hasPowerToReplace(tower, type)
            val ok = game.credits >= cost && powered
            card(c, x, ct, x + unit, cb, if (ok) type.color else 0xFF37474F.toInt(), ok) {
                if (game.replace(tower, type) != null) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            drawTower(c, type, 1, x + unit / 2, ct + 26 * d, 34 * d, -0.6f, game.clock, 10f)
            centerText(c, x + unit / 2, ct + 58 * d, if (powered) "SWAP P${type.power}" else "NO PWR", 9f, if (powered) 0xFF90A4AE.toInt() else 0xFFFF5252.toInt())
            val label = if (cost >= 0) "$cost" else "+${-cost}"
            centerText(c, x + unit / 2, cb - 10 * d, label, 13f, if (game.credits >= cost) 0xFFFFD740.toInt() else 0xFFFF5252.toInt())
            x += unit + gap
        }
    }

    private fun extra(t: Tower) = when (t.type) {
        TowerType.PLASMA -> "SPLASH ${fmt(t.splash)}"
        TowerType.CRYO -> "SLOW ${((1 - t.slowFactor) * 100).toInt()}%"
        TowerType.TESLA -> "CHAIN ${t.chainJumps}"
        TowerType.RAIL -> if (t.level >= AIR_LEVEL) "PIERCE·AIR" else "AIR@LV$AIR_LEVEL"
        TowerType.PULSE -> if (t.level >= AIR_LEVEL) "HITS AIR" else "AIR@LV$AIR_LEVEL"
    }

    private fun fmt(v: Float) = if (v >= 10f) v.toInt().toString() else String.format("%.1f", v)

    private fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float, accent: Int, enabled: Boolean, action: (() -> Unit)?) {
        val cr = RectF(l, t, r, b)
        fill.color = if (enabled) 0xFF0F1A2C.toInt() else 0xFF0B111C.toInt()
        c.drawRoundRect(cr, 10 * d, 10 * d, fill)
        stroke.color = accent; stroke.alpha = if (enabled) 220 else 120; stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(cr, 10 * d, 10 * d, stroke)
        stroke.alpha = 255
        if (action != null) buttons.add(Btn(cr, enabled, action))
    }

    private fun cardText(c: Canvas, x: Float, y: Float, s: String, size: Float, color: Int, maxX: Float) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = size * d
        text.color = color
        var str = s
        while (str.length > 1 && x + text.measureText(str) > maxX) str = str.dropLast(1)
        c.drawText(str, x, y, text)
    }

    private fun centerText(c: Canvas, x: Float, y: Float, s: String, size: Float, color: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size * d
        text.color = color
        c.drawText(s, x, y, text)
    }

    // ---- Overlays ----

    private fun drawBanner(c: Canvas) {
        val msg = banner ?: return
        if (bannerTime > 3f) {
            banner = null; return
        }
        val a = when {
            bannerTime < 0.25f -> bannerTime / 0.25f
            bannerTime > 2.5f -> (3f - bannerTime) / 0.5f
            else -> 1f
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 16 * d
        val tw = text.measureText(msg)
        val y = hudH + 44 * d
        rect.set(width / 2f - tw / 2 - 18 * d, y - 26 * d, width / 2f + tw / 2 + 18 * d, y + 12 * d)
        fill.color = 0xE0101B30.toInt(); fill.alpha = (224 * a).toInt()
        c.drawRoundRect(rect, 19 * d, 19 * d, fill)
        stroke.color = 0xFF64FFDA.toInt(); stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(rect, 19 * d, 19 * d, stroke)
        text.color = 0xFF64FFDA.toInt(); text.alpha = (255 * a).toInt()
        c.drawText(msg, width / 2f, y, text)
        fill.alpha = 255; stroke.alpha = 255; text.alpha = 255
    }

    private fun overlay(c: Canvas, title: String, sub: String, buttonsSpec: List<Pair<String, () -> Unit>>) {
        fill.color = 0xC0000000.toInt()
        c.drawRect(0f, hudH, width.toFloat(), height.toFloat(), fill)
        val cx = width / 2f
        val cy = hudH + (height - hudH) / 2f
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

    private fun drawPause(c: Canvas) {
        overlay(c, "PAUSED", "Wave ${game.wave}  ·  ${game.towers.size} towers  ·  ${game.world.sections.size} sectors", listOf(
            "RESUME" to { paused = false },
            "NEW GAME" to { newGame() },
        ))
    }

    private fun drawGameOver(c: Canvas) {
        overlay(c, "CORE BREACHED", "You held ${game.world.sections.size} sectors until wave ${game.wave}  ·  best ${listener.bestWave()}", listOf(
            "NEW GAME" to { newGame() },
        ))
    }
}
