package com.androidex.starfront

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import com.androidex.starfront.UiKit.Companion.fmt
import com.androidex.starfront.audio.Play
import com.androidex.starfront.audio.Spatializer
import com.androidex.starfront.game.Balance
import com.androidex.starfront.game.Cell
import com.androidex.starfront.game.Game
import com.androidex.starfront.game.GameEvent
import com.androidex.starfront.game.Phase
import com.androidex.starfront.game.ROWS
import com.androidex.starfront.game.SECTION_COLS
import com.androidex.starfront.game.Sfx
import com.androidex.starfront.game.TargetMode
import com.androidex.starfront.game.Tower
import com.androidex.starfront.game.TowerType
import com.androidex.starfront.game.Weapon
import kotlin.math.max
import kotlin.math.min

/**
 * The game screen: runs the loop, turns touches into game actions and draws the HUD, the
 * bottom panel and menus. The battlefield itself is drawn by [WorldRenderer] through [Camera].
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
        fun onVerticalChanged(vertical: Boolean)
    }

    /** Receives the sounds to play each frame; null in headless use. */
    var soundSink: ((Play) -> Unit)? = null
    var muted = false
    private val spatializer = Spatializer()

    private val d = resources.displayMetrics.density
    private val pad = 8 * d
    private val hudRowH = 54 * d
    private val cardRowH = 96 * d

    /** Vertical mode: the HUD and the bottom panel take two rows each. */
    private val vertical get() = height > width
    private val hudH get() = safeT + if (vertical) 2 * hudRowH - 8 * d else hudRowH
    private val panelH get() = if (vertical) 2 * cardRowH + 3 * pad else 108 * d

    // Safe area from display cutouts (a notch on the left or right in landscape, at the top in portrait).
    // Landscape also keeps the side controls out of the back-gesture zones; vertical mode needs the width.
    private var cutL = 0f
    private var cutR = 0f
    private var gestureL = 0f
    private var gestureR = 0f
    private var safeT = 0f
    private val safeL get() = if (vertical) cutL else gestureL
    private val safeR get() = if (vertical) cutR else gestureR

    // Selection: a tower or an empty buildable cell.
    private var selected = false
    private var selCol = 0
    private var selRow = 0
    // Tower type previewed on the selected empty cell: first tap on a build card previews, second buys.
    private var preview: TowerType? = null

    private val camera = Camera({ hudH }, { game.world }, { if (selected) panelH else 0f })
    private val sprites = Sprites(d)
    private val renderer = WorldRenderer(d, camera, sprites)
    private val ui = UiKit(d)

    // Loop
    var running = false
        set(value) {
            field = value
            lastFrame = System.nanoTime()
            if (value) postInvalidateOnAnimation()
        }
    private var lastFrame = System.nanoTime()
    private var paused = false
    // Speed setting shown on the HUD (x1/x2). The old real-time x1 was too slow, so x1 runs the simulation at 2x and x2 at 3x.
    private var speed = 1
    private var autoLaunch = false
    private var autoTimer = 0f

    // Banner messages
    private var banner: String? = null
    private var bannerTime = 0f

    private val minimap = RectF()

    private enum class Region { MAP, HUD, MINIMAP, PANEL }
    private var touchRegion = Region.MAP

    // Shorthands for the UI paints used by the HUD and panel code below.
    private val fill get() = ui.fill
    private val stroke get() = ui.stroke
    private val text get() = ui.text
    private val path get() = ui.path
    private val rect get() = ui.rect

    /** Routes the game's sounds into the spatializer, which keeps what's audible this frame. */
    private fun listenTo(g: Game) {
        g.feedback.onSound = spatializer::offer
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            camera.stopFling()
            touchRegion = regionAt(e.x, e.y)
            if (touchRegion == Region.MAP) camera.targetX = null
            if (touchRegion == Region.MINIMAP) jumpMinimap(e.x)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y); return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            when (touchRegion) {
                Region.MAP -> camera.pan(dx, dy)
                Region.MINIMAP -> jumpMinimap(e2.x)
                else -> {}
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (touchRegion == Region.MAP) camera.fling(vx, vy)
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            if (touchRegion == Region.MAP) camera.zoom(det.scaleFactor, det.focusX, det.focusY)
            return true
        }
    })

    init {
        listenTo(game)
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemGestures())
            gestureL = cut.left.toFloat()
            gestureR = cut.right.toFloat()
            val notch = insets.getInsets(WindowInsets.Type.displayCutout())
            cutL = notch.left.toFloat()
            cutR = notch.right.toFloat()
            safeT = notch.top.toFloat()
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
        listenTo(game)
        selected = false
        paused = false
        speed = 1
        camera.reset()
        listener.onSave(game.saveState())
        showBanner("NEW COLONY ESTABLISHED")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.resize(h)
        // Switching between vertical and horizontal starts the view afresh.
        camera.resize(w, h, first = oldw == 0 || (w > h) != (oldw > oldh))
    }

    private fun jumpMinimap(x: Float) = camera.jumpTo(((x - minimap.left) / minimap.width()).coerceIn(0f, 1f))

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
        val b = ui.hit(x, y)
        if (b != null) {
            if (b.enabled) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                b.action()
            } else {
                game.sound(Sfx.DENIED)
            }
            return
        }
        if (regionAt(x, y) != Region.MAP) return
        preview = null
        val col = camera.colAt(x)
        val row = camera.rowAt(y)
        if (selected && col == selCol && row == selRow) {
            selected = false
        } else if (game.towerAt(col, row) != null || game.canBuild(col, row)) {
            selected = true
            selCol = col
            selRow = row
            camera.ensureVisible(col, row, panelH)
        } else {
            selected = false
        }
        camera.clamp()
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
        ui.beginFrame()
        renderer.draw(canvas, game, if (selected) Cell(selCol, selRow) else null, preview, width.toFloat(), height.toFloat())
        drawHint(canvas)
        drawHud(canvas)
        if (selected && !paused) drawPanel(canvas)
        drawBanner(canvas)
        if (game.phase == Phase.GAME_OVER) drawGameOver(canvas) else if (paused) drawPause(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        spatializer.setView(camera.x, camera.y, camera.x + camera.viewW, camera.y + camera.viewH)
        if (running && !paused) {
            var remaining = dt * (speed + 1)
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
                    camera.targetX = ev.section.startCol - SECTION_COLS * 0.35f
                    listener.onSave(game.saveState())
                }
                is GameEvent.GameOver -> {
                    selected = false
                    listener.onGameOver(ev.wave)
                }
            }
        }
        camera.step(dt)
        bannerTime += dt
        playSounds()
    }

    /** Plays this frame's sounds from what's on screen: off-screen sources stay silent. */
    private fun playSounds() {
        val plays = spatializer.finish(System.nanoTime() / 1e9)
        val sink = soundSink ?: return
        if (!muted) for (p in plays) sink(p)
    }

    private fun launch() {
        if (game.phase != Phase.BUILD) return
        autoTimer = 0f
        game.startWave()
        showBanner("WAVE ${game.wave} INBOUND")
    }

    // ---- Hints ----

    private fun drawHint(c: Canvas) {
        if (game.wave == 0 && game.towers.size < 2 && !selected) {
            hint(c, "TAP A TILE BESIDE THE PATH TO BUILD  ·  EVERY ${Balance.WAVES_PER_SECTOR} WAVES A NEW SECTOR OPENS WEST")
        } else if (game.wave in 1..2 && game.phase == Phase.BUILD && !selected) {
            hint(c, "SWIPE EAST TO UPGRADE OR SWAP OLDER TOWERS  ·  PINCH TO ZOOM  ·  TAP MINIMAP TO JUMP")
        }
    }

    private fun hint(c: Canvas, msg: String) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 12 * d
        text.color = 0xCCB0BEC5.toInt()
        val lines = ui.wrap(msg, width - 40 * d)
        val lineH = 18 * d
        val y = hudH + 96 * d
        val tw = lines.maxOf { text.measureText(it) }
        rect.set(width / 2f - tw / 2 - 12 * d, y - 18 * d, width / 2f + tw / 2 + 12 * d, y + 8 * d + (lines.size - 1) * lineH)
        fill.color = 0xCC0A0F1C.toInt()
        c.drawRoundRect(rect, 12 * d, 12 * d, fill)
        lines.forEachIndexed { i, line -> c.drawText(line, width / 2f, y + i * lineH, text) }
    }

    // ---- HUD ----

    private fun drawHud(c: Canvas) {
        val w = width.toFloat()
        fill.color = 0xE6070B16.toInt()
        c.drawRect(0f, 0f, w, hudH, fill)
        stroke.color = 0xFF1E3A5F.toInt(); stroke.strokeWidth = 1.5f * d
        c.drawLine(0f, hudH, w, hudH, stroke)

        // Stats on the first row; the buttons share it in landscape and get a second row in vertical mode.
        val cy = safeT + hudRowH / 2
        var x = safeL + 12 * d
        text.textAlign = Paint.Align.LEFT
        text.textSize = 17 * d

        // Credits
        fill.color = 0xFFFFD740.toInt()
        path.regularPolygon(x + 7 * d, cy, 7 * d, 4, 0f)
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

        // Buttons, laid out from the right edge.
        val right = w - safeR - 8 * d
        var rx = right
        val bh = hudRowH - 14 * d
        val by = safeT + if (vertical) hudRowH - 2 * d else 7 * d
        val bcy = by + bh / 2

        // In vertical mode the launch button takes whatever the toggles leave.
        val launchW = if (vertical) right - (safeL + 8 * d) - (52 + 52 + 48 + 44 + 4 * 6) * d else 136 * d
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
            ui.buttons.add(UiKit.Btn(r, true) { launch() })
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
        ui.hudButton(c, rx - sndW, by, rx, by + bh, if (muted) "OFF" else "SFX", !muted) {
            muted = !muted
            listener.onMuteChanged(muted)
        }
        rx -= sndW + 6 * d

        // Auto-launch toggle
        val autoW = 52 * d
        ui.hudButton(c, rx - autoW, by, rx, by + bh, "AUTO", autoLaunch) { autoLaunch = !autoLaunch }
        rx -= autoW + 6 * d
        val speedW = 48 * d
        ui.hudButton(c, rx - speedW, by, rx, by + bh, "x$speed", speed > 1) { speed = if (speed >= 2) 1 else 2 }
        rx -= speedW + 6 * d
        val pauseW = 44 * d
        ui.hudButton(c, rx - pauseW, by, rx, by + bh, "", paused) { paused = !paused }
        fill.color = Color.WHITE
        val pcx = rx - pauseW / 2
        c.drawRect(pcx - 6 * d, bcy - 8 * d, pcx - 2 * d, bcy + 8 * d, fill)
        c.drawRect(pcx + 2 * d, bcy - 8 * d, pcx + 6 * d, bcy + 8 * d, fill)
        rx -= pauseW + 12 * d

        // Minimap fills the gap after the stats.
        minimap.set(x, safeT + 8 * d, if (vertical) right else rx, safeT + hudRowH - 8 * d)
        if (minimap.width() > 60 * d) drawMinimap(c) else minimap.setEmpty()
    }

    private fun drawMinimap(c: Canvas) {
        val m = minimap
        val left = camera.worldLeft
        val span = camera.worldRight - left
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
        c.drawRect(mx(camera.x).coerceAtLeast(m.left), m.top, mx(camera.x + camera.viewW).coerceAtMost(m.right), m.bottom, stroke)
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
        if (tower == null) drawBuildCards(c, l, r, ct, cb) else drawTowerCards(c, tower, l, r, ct, cb)
    }

    /**
     * One card per tower type for an empty cell, in one row (or rows of three in vertical mode).
     * The first tap on a card previews that tower (ghost and range on the map); a second tap on
     * the same card buys it.
     */
    private fun drawBuildCards(c: Canvas, l: Float, r: Float, ct: Float, cb: Float) {
        val types = TowerType.entries
        val perRow = if (vertical) 3 else types.size
        val rows = (types.size + perRow - 1) / perRow
        val cw = min(190 * d, (r - l - pad * (perRow - 1)) / perRow)
        val ch = (cb - ct - pad * (rows - 1)) / rows
        // Narrow cards stack the title under the icon instead of beside it.
        val compact = cw < 150 * d
        for ((i, type) in types.withIndex()) {
            val x = l + (i % perRow) * (cw + pad)
            val t = ct + (i / perRow) * (ch + pad)
            val b = t + ch
            val powered = game.hasPowerFor(type, selCol)
            val ok = game.credits >= type.cost && powered
            val previewing = preview == type
            ui.card(c, x, t, x + cw, b, if (ok || previewing) type.color else 0xFF37474F.toInt(), true, dimmed = !ok, highlight = previewing) {
                when {
                    !previewing -> preview = type
                    game.build(type, selCol, selRow) != null -> {
                        preview = null
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                    else -> game.sound(Sfx.DENIED)
                }
            }
            val status = when {
                !previewing -> type.blurb
                !powered -> "NO POWER"
                !ok -> "NEED CREDITS"
                else -> "TAP TO BUY"
            }
            val statusColor = if (!previewing) 0xFF90A4AE.toInt() else if (ok) 0xFF69F0AE.toInt() else 0xFFFF5252.toInt()
            val costColor = if (game.credits >= type.cost) 0xFFFFD740.toInt() else 0xFFFF5252.toInt()
            val powerColor = if (powered) 0xFF40C4FF.toInt() else 0xFFFF5252.toInt()
            if (compact) {
                sprites.tower(c, type, 1, x + 24 * d, t + 24 * d, 36 * d, -0.6f, game.clock, 10f)
                ui.cardText(c, x + 50 * d, t + 30 * d, "${type.cost}", 15f, costColor, x + cw)
                ui.cardText(c, x + 10 * d, t + 56 * d, type.title.uppercase(), 11f, Color.WHITE, x + cw - 6 * d)
                ui.cardText(c, x + 10 * d, t + 72 * d, status, 10f, statusColor, x + cw - 6 * d)
                ui.cardText(c, x + 10 * d, b - 9 * d, "R${fmt(type.range)}", 10f, 0xFF7FA7D9.toInt(), x + cw)
                ui.cardText(c, x + cw * 0.55f, b - 9 * d, "P${type.power}", 10f, powerColor, x + cw)
            } else {
                sprites.tower(c, type, 1, x + 28 * d, t + 30 * d, 44 * d, -0.6f, game.clock, 10f)
                ui.cardText(c, x + 58 * d, t + 22 * d, type.title.uppercase(), 11f, Color.WHITE, x + cw - 6 * d)
                ui.cardText(c, x + 58 * d, t + 40 * d, status, 10f, statusColor, x + cw - 6 * d)
                ui.cardText(c, x + 10 * d, b - 12 * d, "${type.cost}", 15f, costColor, x + cw)
                ui.cardText(c, x + cw * 0.42f, b - 12 * d, "R${fmt(type.range)}", 10f, 0xFF7FA7D9.toInt(), x + cw)
                ui.cardText(c, x + cw * 0.7f, b - 12 * d, "P${type.power}", 10f, powerColor, x + cw)
            }
        }
    }

    /**
     * Info, upgrade, targeting and sell for a tower, then a swap card per other type: all in one
     * row, or with the swaps on a second row in vertical mode.
     */
    private fun drawTowerCards(c: Canvas, tower: Tower, l: Float, r: Float, ct: Float, panelB: Float) {
        val others = TowerType.entries.filter { it != tower.type }
        val gap = pad * 0.75f
        // In vertical mode the info card takes a little more of its row so its stats line fits.
        val infoUnits = if (vertical) 2.7f else 2.4f
        val mainUnits = infoUnits + 1.25f + 1f + 1f
        val cb = if (vertical) (ct + panelB - pad) / 2 else panelB
        val unit = if (vertical) (r - l - gap * 3) / mainUnits else min(120 * d, (r - l - gap * (3 + others.size)) / (mainUnits + others.size))
        var x = l

        val iw = unit * infoUnits
        ui.card(c, x, ct, x + iw, cb, tower.type.color, true, action = null)
        sprites.tower(c, tower.type, tower.level, x + 30 * d, ct + 32 * d, 46 * d, tower.angle, game.clock, 10f)
        ui.cardText(c, x + 60 * d, ct + 20 * d, tower.type.title.uppercase(), 12f, Color.WHITE, x + iw - 4 * d)
        ui.cardText(c, x + 60 * d, ct + 37 * d, "LV ${tower.level}/${Balance.MAX_LEVEL} · ${tower.kills} KILLS", 10f, 0xFFFFD740.toInt(), x + iw - 4 * d)
        ui.cardText(c, x + 10 * d, cb - 26 * d, "DMG ${fmt(tower.damage)}  RNG ${fmt(tower.range)}", 10f, 0xFFB0BEC5.toInt(), x + iw - 4 * d)
        ui.cardText(c, x + 10 * d, cb - 10 * d, "RATE ${fmt(tower.rate)}/s  ${traits(tower)}", 10f, 0xFFB0BEC5.toInt(), x + iw - 4 * d)
        x += iw + gap

        val uw = unit * 1.25f
        val up = tower.upgradeCost
        val canUp = up != null && game.credits >= up
        ui.card(c, x, ct, x + uw, cb, if (canUp) 0xFF69F0AE.toInt() else 0xFF37474F.toInt(), canUp) {
            if (game.upgrade(tower)) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        ui.centerText(c, x + uw / 2, ct + 24 * d, "UPGRADE", 12f, Color.WHITE)
        if (up == null) {
            ui.centerText(c, x + uw / 2, ct + 52 * d, "MAX", 15f, 0xFF69F0AE.toInt())
        } else {
            ui.centerText(c, x + uw / 2, ct + 50 * d, "$up", 16f, if (canUp) 0xFFFFD740.toInt() else 0xFFFF5252.toInt())
            ui.centerText(c, x + uw / 2, ct + 70 * d, "DMG→${fmt(tower.nextDamage)}", 9f, 0xFF90A4AE.toInt())
        }
        x += uw + gap

        ui.card(c, x, ct, x + unit, cb, 0xFF64B5F6.toInt(), true) {
            tower.mode = TargetMode.entries[(tower.mode.ordinal + 1) % TargetMode.entries.size]
        }
        ui.centerText(c, x + unit / 2, ct + 24 * d, "TARGET", 11f, Color.WHITE)
        ui.centerText(c, x + unit / 2, ct + 52 * d, tower.mode.label, 12f, 0xFF64B5F6.toInt())
        x += unit + gap

        ui.card(c, x, ct, x + unit, cb, 0xFFFF8A80.toInt(), true) {
            game.sell(tower)
            selected = false
        }
        ui.centerText(c, x + unit / 2, ct + 24 * d, "SELL", 11f, Color.WHITE)
        ui.centerText(c, x + unit / 2, ct + 52 * d, "+${tower.sellValue}", 14f, 0xFFFFD740.toInt())
        x += unit + gap

        if (vertical) drawSwapCards(c, tower, others, l, r, cb + pad, panelB, null, gap)
        else drawSwapCards(c, tower, others, x, r, ct, panelB, unit, gap)
    }

    /** One card per other type that [tower] can be swapped for, [cardW] wide or (when null) sharing the row between [l] and [r]. */
    private fun drawSwapCards(c: Canvas, tower: Tower, others: List<TowerType>, l: Float, r: Float, ct: Float, cb: Float, cardW: Float?, gap: Float) {
        val unit = cardW ?: min(120 * d, (r - l - gap * (others.size - 1)) / others.size)
        var x = l
        for (type in others) {
            val cost = game.replaceCost(tower, type)
            val powered = game.hasPowerToReplace(tower, type)
            val ok = game.credits >= cost && powered
            ui.card(c, x, ct, x + unit, cb, if (ok) type.color else 0xFF37474F.toInt(), ok) {
                if (game.replace(tower, type) != null) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            sprites.tower(c, type, 1, x + unit / 2, ct + 26 * d, 34 * d, -0.6f, game.clock, 10f)
            ui.centerText(c, x + unit / 2, ct + 58 * d, if (powered) "SWAP P${type.power}" else "NO PWR", 9f, if (powered) 0xFF90A4AE.toInt() else 0xFFFF5252.toInt())
            val label = if (cost >= 0) "$cost" else "+${-cost}"
            ui.centerText(c, x + unit / 2, cb - 10 * d, label, 13f, if (game.credits >= cost) 0xFFFFD740.toInt() else 0xFFFF5252.toInt())
            x += unit + gap
        }
    }

    /** The weapon's special stat, and whether (or from which level) it can hit air. */
    private fun traits(t: Tower): String {
        val weapon = when (t.type.weapon) {
            Weapon.MORTAR -> "SPLASH ${fmt(t.splash)}"
            Weapon.FROST -> "SLOW ${((1 - t.slowFactor) * 100).toInt()}%"
            Weapon.ARC -> "CHAIN ${t.chainJumps}"
            Weapon.RAIL -> "PIERCE"
            Weapon.BOLT -> ""
        }
        val air = t.type.airFromLevel
        return when {
            // Air from the start isn't worth a mention; unlocking it later is.
            air == null || air <= 1 -> weapon
            t.level < air -> "AIR@LV$air"
            weapon.isEmpty() -> "HITS AIR"
            else -> "$weapon·AIR"
        }
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

    private fun drawPause(c: Canvas) {
        ui.overlay(c, hudH, width.toFloat(), height.toFloat(), "PAUSED", "Wave ${game.wave}  ·  ${game.towers.size} towers  ·  ${game.world.sections.size} sectors", listOf(
            "RESUME" to { paused = false },
            "NEW GAME" to { newGame() },
            (if (vertical) "HORIZONTAL" else "VERTICAL") to { listener.onVerticalChanged(!vertical) },
        ))
    }

    private fun drawGameOver(c: Canvas) {
        ui.overlay(c, hudH, width.toFloat(), height.toFloat(), "CORE BREACHED", "You held ${game.world.sections.size} sectors until wave ${game.wave}  ·  best ${listener.bestWave()}", listOf(
            "NEW GAME" to { newGame() },
        ))
    }
}
