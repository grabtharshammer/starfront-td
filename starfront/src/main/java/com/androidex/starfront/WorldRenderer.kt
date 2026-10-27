package com.androidex.starfront

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.androidex.starfront.game.BARRIER_COLOR
import com.androidex.starfront.game.Balance
import com.androidex.starfront.game.Cell
import com.androidex.starfront.game.FxKind
import com.androidex.starfront.game.Game
import com.androidex.starfront.game.Layer
import com.androidex.starfront.game.ROWS
import com.androidex.starfront.game.SECTION_COLS
import com.androidex.starfront.game.Section
import com.androidex.starfront.game.ShotKind
import com.androidex.starfront.game.TowerType
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the battlefield through [camera]: space backdrop, sectors, path, power gauges, warp gate,
 * core, towers, enemies, shots and effects. [d] is the display density.
 */
class WorldRenderer(private val d: Float, private val camera: Camera, private val sprites: Sprites) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val gridPaint = Paint().apply { color = 0xFF16233A.toInt(); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); color = Color.WHITE }
    private val bg = Paint()
    private val path = Path()
    private val rect = RectF()
    /** Cosmetic randomness only (star field, lightning jitter). */
    private val rng = Random(1)

    private class Star(val u: Float, val v: Float, val depth: Float, val size: Float, val twinkle: Float)
    private val stars = List(170) { Star(rng.nextFloat(), rng.nextFloat(), 0.05f + rng.nextFloat() * 0.3f, 0.6f + rng.nextFloat() * 1.6f, rng.nextFloat() * 6f) }

    private val scale get() = camera.scale
    private fun sx(wx: Float) = camera.sx(wx)
    private fun sy(wy: Float) = camera.sy(wy)

    fun resize(h: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF04050D.toInt(), 0xFF0D0820.toInt(), Shader.TileMode.CLAMP)
    }

    /** Draws the whole field; [selection] is the highlighted cell, if any, and [preview] a tower type being considered for it. */
    fun draw(c: Canvas, game: Game, selection: Cell?, preview: TowerType?, w: Float, h: Float) {
        val world = game.world
        val time = game.clock
        c.drawRect(0f, 0f, w, h, bg)
        drawStars(c, w, h, time)

        val c0 = max(floor(camera.x).toInt() - 1, world.minCol - SECTION_COLS)
        val c1 = floor(camera.x + camera.viewW).toInt() + 1

        drawUncharted(c, game, time)
        drawSectors(c, game, c0, c1)
        drawRoute(c, game, time)

        for (sec in world.sections) if (sec.endCol >= c0 && sec.startCol <= c1) drawPowerBolt(c, game, sec, time)
        drawGate(c, world.minCol - 1f, world.frontier.entryRow + 0.5f, time)
        drawCore(c, game, world.coreCol + 0.5f, world.coreRow + 0.5f, time)
        drawTankBay(c, game, time)

        if (selection != null) drawSelection(c, game, selection, preview, time)

        val vx0 = camera.x - 1.5f
        val vx1 = camera.x + camera.viewW + 1.5f
        for (t in game.towers) if (t.x in vx0..vx1) sprites.tower(c, t.type, t.level, sx(t.x), sy(t.y), scale, t.angle, time, t.sinceShot)
        for (e in game.enemies) if (e.type.layer == Layer.GROUND && e.x in vx0..vx1) sprites.enemy(c, e, sx(e.x), sy(e.y), scale, time)
        game.tanks.tank?.let { if (it.x in vx0..vx1) sprites.tank(c, it, sx(it.x), sy(it.y), scale, time) }
        drawProjectiles(c, game, vx0, vx1)
        // Air units fly above everything on the ground.
        for (e in game.enemies) if (e.type.layer == Layer.AIR && e.x in vx0..vx1) sprites.enemy(c, e, sx(e.x), sy(e.y), scale, time)
        drawEffects(c, game)
    }

    private fun drawStars(c: Canvas, w: Float, h: Float, time: Float) {
        for (s in stars) {
            val x = ((s.u * w * 3f - camera.x * scale * s.depth) % w + w) % w
            val y = s.v * h - camera.y * scale * s.depth * 0.5f
            fill.color = Color.WHITE
            fill.alpha = (110 + 90 * sin(time * 1.3f + s.twinkle)).toInt().coerceIn(20, 255)
            c.drawCircle(x, y, s.size * d * 0.6f, fill)
        }
        fill.alpha = 255
    }

    /** The next sector's outline beyond the frontier. */
    private fun drawUncharted(c: Canvas, game: Game, time: Float) {
        val world = game.world
        val gx0 = sx(world.minCol - SECTION_COLS.toFloat())
        val gx1 = sx(world.minCol.toFloat())
        if (gx1 <= 0) return
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

    /** Sector floors and labels, the grid, seams and path tiles for visible columns [c0]..[c1]. */
    private fun drawSectors(c: Canvas, game: Game, c0: Int, c1: Int) {
        val world = game.world
        for (s in world.sections) {
            if (s.endCol < c0 || s.startCol > c1) continue
            fill.color = if (s.index % 2 == 0) 0xFF0A1424.toInt() else 0xFF0D1122.toInt()
            c.drawRect(sx(s.startCol.toFloat()), sy(0f), sx(s.endCol.toFloat()), sy(ROWS.toFloat()), fill)
            text.textAlign = Paint.Align.LEFT
            text.textSize = 11 * d
            text.color = 0x887FA7D9.toInt()
            c.drawText("SECTOR ${s.index + 1}", sx(s.startCol.toFloat()) + 4 * d, sy(0f) - 6 * d, text)
        }
        val gx = max(c0, world.minCol)
        val gEnd = min(c1, world.coreCol)
        for (col in gx..gEnd) c.drawLine(sx(col.toFloat()), sy(0f), sx(col.toFloat()), sy(ROWS.toFloat()), gridPaint)
        for (row in 0..ROWS) c.drawLine(sx(gx.toFloat()), sy(row.toFloat()), sx(gEnd.toFloat()), sy(row.toFloat()), gridPaint)
        stroke.color = 0x553A5F8F
        stroke.strokeWidth = 2 * d
        for (s in world.sections) c.drawLine(sx(s.startCol.toFloat()), sy(-0.3f), sx(s.startCol.toFloat()), sy(ROWS + 0.3f), stroke)
        fill.color = 0xFF161034.toInt()
        for (s in world.sections) {
            if (s.endCol < c0 || s.startCol > c1) continue
            for (cell in s.path) c.drawRect(sx(cell.col.toFloat()) + 1, sy(cell.row.toFloat()) + 1, sx(cell.col + 1f) - 1, sy(cell.row + 1f) - 1, fill)
        }
    }

    /** The glowing energy line along the enemy route, with dashes flowing toward the core. */
    private fun drawRoute(c: Canvas, game: Game, time: Float) {
        path.reset()
        val wp = game.world.waypoints
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
    }

    /**
     * Lightning bolt along the bottom edge of a sector: its length is the power still free
     * there, over a dim track showing the full budget.
     */
    private fun drawPowerBolt(c: Canvas, game: Game, sec: Section, time: Float) {
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
            left <= 0 -> "MAX ALL +${Balance.EFFICIENCY_BONUS}"
            else -> null
        }
        if (note != null) {
            text.color = BARRIER_COLOR
            text.textSize = 9 * d
            c.drawText(note, sx(x0 + full) + 6 * d, sy(y) - 9 * d, text)
        }
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

    private fun drawCore(c: Canvas, game: Game, wx: Float, wy: Float, time: Float) {
        val x = sx(wx)
        val y = sy(wy)
        val health = game.lives / 20f
        val col = if (health > 0.5f) 0xFF18FFFF.toInt() else if (health > 0.25f) 0xFFFFD740.toInt() else 0xFFFF1744.toInt()
        fill.color = col; fill.alpha = 40
        c.drawCircle(x, y, scale * (0.75f + 0.05f * sin(time * 2f)), fill)
        fill.alpha = 255
        fill.color = 0xFF16213A.toInt()
        path.regularPolygon(x, y, scale * 0.55f, 6, time * 0.2f)
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

    /** The pad south of the core with the parked tank, its count, and a launch prompt during a wave. */
    private fun drawTankBay(c: Canvas, game: Game, time: Float) {
        val bay = game.tanks
        if (bay.charges <= 0) return
        val x = sx(bay.parkX)
        val y = sy(bay.parkY)
        val half = scale * 0.48f
        rect.set(x - half, y - half, x + half, y + half)
        fill.color = 0xFF0F1A2C.toInt()
        c.drawRoundRect(rect, scale * 0.12f, scale * 0.12f, fill)
        stroke.color = Balance.TANK_COLOR
        stroke.strokeWidth = 1.5f * d
        stroke.alpha = if (bay.canLaunch) (150 + 100 * sin(time * 5f)).toInt() else 90
        stroke.pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
        c.drawRoundRect(rect, scale * 0.12f, scale * 0.12f, stroke)
        stroke.pathEffect = null
        stroke.alpha = 255
        sprites.parkedTank(c, x, y, scale, time)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 10 * d
        text.color = Balance.TANK_COLOR
        if (bay.charges > 1) c.drawText("x${bay.charges}", x + half, y - half - 3 * d, text)
        if (bay.canLaunch) {
            text.alpha = (170 + 85 * sin(time * 5f)).toInt()
            c.drawText("TAP TO LAUNCH", x, y + half + 13 * d, text)
            text.alpha = 255
        } else {
            text.color = 0x9969F0AE.toInt()
            c.drawText("TANK", x, y + half + 13 * d, text)
        }
    }

    /**
     * Pulsing outline on the selected cell, plus the range of the tower on it, or of the
     * [preview] type (drawn as a ghost) when the cell is empty.
     */
    private fun drawSelection(c: Canvas, game: Game, sel: Cell, preview: TowerType?, time: Float) {
        val t = game.towerAt(sel.col, sel.row)
        val cx = sx(sel.col + 0.5f)
        val cy = sy(sel.row + 0.5f)
        if (t != null) {
            drawRange(c, cx, cy, t.range, t.type.color)
        } else if (preview != null) {
            drawRange(c, cx, cy, Balance.range(preview.range, 1), preview.color)
            c.saveLayerAlpha(null, 120)
            sprites.tower(c, preview, 1, cx, cy, scale, -0.6f, time, 10f)
            c.restore()
        }
        stroke.color = Color.WHITE
        stroke.strokeWidth = 2 * d
        stroke.alpha = (180 + 70 * sin(time * 6f)).toInt()
        c.drawRect(sx(sel.col.toFloat()) + 2, sy(sel.row.toFloat()) + 2, sx(sel.col + 1f) - 2, sy(sel.row + 1f) - 2, stroke)
        stroke.alpha = 255
    }

    private fun drawRange(c: Canvas, x: Float, y: Float, range: Float, color: Int) {
        fill.color = color; fill.alpha = 28
        c.drawCircle(x, y, range * scale, fill)
        fill.alpha = 255
        stroke.color = color; stroke.alpha = 140; stroke.strokeWidth = 1.5f * d
        c.drawCircle(x, y, range * scale, stroke)
        stroke.alpha = 255
    }

    private fun drawProjectiles(c: Canvas, game: Game, vx0: Float, vx1: Float) {
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
    }

    /** Beams, arcs, rings and blasts, then debris and floating numbers. */
    private fun drawEffects(c: Canvas, game: Game) {
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
    }
}
