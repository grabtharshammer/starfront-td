package com.androidex.starfront

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.androidex.starfront.game.BARRIER_COLOR
import com.androidex.starfront.game.Balance
import com.androidex.starfront.game.Enemy
import com.androidex.starfront.game.EnemyType
import com.androidex.starfront.game.Layer
import com.androidex.starfront.game.Tank
import com.androidex.starfront.game.TowerType
import com.androidex.starfront.game.Weapon
import kotlin.math.cos
import kotlin.math.sin

/** Builds a regular polygon centred on ([x], [y]) into this path. */
fun Path.regularPolygon(x: Float, y: Float, r: Float, sides: Int, rot: Float) {
    reset()
    for (i in 0 until sides) {
        val a = rot + i * 2f * Math.PI.toFloat() / sides
        val px = x + cos(a) * r
        val py = y + sin(a) * r
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

/** Tower, enemy and tank artwork, drawn at screen positions. [d] is the display density. */
class Sprites(private val d: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rect = RectF()

    /** A tower of [type] at ([x], [y]) on a tile [s] px wide, turret facing [angle] radians. */
    fun tower(c: Canvas, type: TowerType, level: Int, x: Float, y: Float, s: Float, angle: Float, time: Float, sinceShot: Float) {
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
                path.regularPolygon(x, y, s * 0.22f, 8, 0f)
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
        if (sinceShot < 0.07f && hasBarrel(type.weapon)) {
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

    /** Weapons that fire out of a barrel get a muzzle flash. */
    private fun hasBarrel(w: Weapon) = when (w) {
        Weapon.BOLT, Weapon.MORTAR, Weapon.RAIL -> true
        Weapon.FROST, Weapon.ARC -> false
    }

    /** The player's tank [t] at screen position ([x], [y]), with a health bar once damaged; [scale] is px per cell. */
    fun tank(c: Canvas, t: Tank, x: Float, y: Float, scale: Float, time: Float) {
        tankBody(c, x, y, scale, t.heading, t.aim, time, moving = !t.blocked, sinceShot = t.sinceShot)
        if (t.sinceHit < 0.08f) {
            fill.color = 0xFFFF5252.toInt(); fill.alpha = 110
            c.drawCircle(x, y, t.radius * scale * 1.1f, fill)
            fill.alpha = 255
        }
        if (t.hp < t.maxHp) {
            val bw = scale * 0.8f
            val by = y - t.radius * scale - scale * 0.22f
            fill.color = 0xCC000000.toInt()
            c.drawRect(x - bw / 2, by, x + bw / 2, by + 4 * d, fill)
            val f = (t.hp / t.maxHp).coerceIn(0f, 1f)
            fill.color = if (f > 0.5f) Balance.TANK_COLOR else if (f > 0.25f) 0xFFFFD740.toInt() else 0xFFFF5252.toInt()
            c.drawRect(x - bw / 2, by, x - bw / 2 + bw * f, by + 4 * d, fill)
        }
    }

    /** A tank waiting in its bay, facing north toward the core. */
    fun parkedTank(c: Canvas, x: Float, y: Float, scale: Float, time: Float) {
        val up = -Math.PI.toFloat() / 2
        tankBody(c, x, y, scale, up, up, time, moving = false, sinceShot = 10f)
    }

    private fun tankBody(c: Canvas, x: Float, y: Float, s: Float, heading: Float, aim: Float, time: Float, moving: Boolean, sinceShot: Float) {
        val r = Balance.TANK_RADIUS * s
        val col = Balance.TANK_COLOR
        c.save()
        c.rotate(Math.toDegrees(heading.toDouble()).toFloat(), x, y)
        // Treads, with links that roll while it drives.
        fill.color = 0xFF263238.toInt()
        rect.set(x - r * 1.1f, y - r * 0.95f, x + r * 1.1f, y - r * 0.5f)
        c.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
        rect.set(x - r * 1.1f, y + r * 0.5f, x + r * 1.1f, y + r * 0.95f)
        c.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
        stroke.color = 0xFF546E7A.toInt(); stroke.alpha = 255; stroke.strokeWidth = r * 0.07f
        val roll = if (moving) (time * 3f) % 1f else 0f
        for (i in 0 until 5) {
            val lx = x - r * 1.0f + (i + roll) * r * 0.42f
            if (lx > x + r * 1.05f) continue
            c.drawLine(lx, y - r * 0.92f, lx, y - r * 0.53f, stroke)
            c.drawLine(lx, y + r * 0.53f, lx, y + r * 0.92f, stroke)
        }
        // Hull
        fill.color = 0xFF1B5E20.toInt()
        rect.set(x - r * 0.95f, y - r * 0.6f, x + r * 0.95f, y + r * 0.6f)
        c.drawRoundRect(rect, r * 0.25f, r * 0.25f, fill)
        stroke.color = col; stroke.strokeWidth = r * 0.09f
        c.drawRoundRect(rect, r * 0.25f, r * 0.25f, stroke)
        c.restore()
        // Turret and barrel, turned toward its target.
        c.save()
        c.rotate(Math.toDegrees(aim.toDouble()).toFloat(), x, y)
        stroke.color = 0xFFB0BEC5.toInt(); stroke.strokeWidth = r * 0.2f
        stroke.strokeCap = Paint.Cap.BUTT
        c.drawLine(x, y, x + r * 1.25f, y, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        fill.color = 0xFF2E7D32.toInt()
        c.drawCircle(x, y, r * 0.42f, fill)
        fill.color = col
        c.drawCircle(x, y, r * 0.18f, fill)
        if (sinceShot < 0.07f) {
            fill.color = Color.WHITE
            c.drawCircle(x + r * 1.3f, y, r * 0.22f, fill)
        }
        c.restore()
    }

    /** Enemy [e] at screen position ([x], [y]), with status rings and health bars; [scale] is px per cell. */
    fun enemy(c: Canvas, e: Enemy, x: Float, y: Float, scale: Float, time: Float) {
        val r = e.radius * scale
        val col = e.type.color
        if (e.type.layer == Layer.AIR) {
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
                path.regularPolygon(x, y, r, 6, 0f)
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
            path.regularPolygon(x, y, r * 1.7f, 6, time * 1.5f)
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
}
