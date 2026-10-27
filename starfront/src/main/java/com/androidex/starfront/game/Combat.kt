package com.androidex.starfront.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Towers picking targets and firing, projectiles in flight, and damage and kill rewards. */
class Combat(private val game: Game) {
    val projectiles = ArrayList<Projectile>()

    private val feedback get() = game.feedback
    private val enemies get() = game.enemies

    /** Whether [t] is able to shoot [e] at all. */
    fun canHit(t: Tower, e: Enemy) = t.reaches(e.type.layer)

    fun update(dt: Float) {
        updateTowers(dt)
        updateProjectiles(dt)
    }

    fun clear() = projectiles.clear()

    private fun updateTowers(dt: Float) {
        for (t in game.towers) {
            t.sinceShot += dt
            t.cooldown -= dt
            if (t.cooldown > 0f) continue
            val target = pickTarget(t) ?: continue
            t.cooldown = 1f / t.rate
            t.sinceShot = 0f
            fire(t, target)
            feedback.sound(t.type.fireSfx, t.x, t.y)
        }
    }

    private fun fire(t: Tower, target: Enemy) {
        if (t.type.weapon.aims) t.angle = atan2(target.y - t.y, target.x - t.x)
        val mx = t.x + cos(t.angle) * 0.35f
        val my = t.y + sin(t.angle) * 0.35f
        val hitsAir = t.reaches(Layer.AIR)
        when (t.type.weapon) {
            Weapon.BOLT -> projectiles.add(Projectile(ShotKind.BOLT, mx, my, target, t.type.shotSpeed, t.damage, 0f, t.type.color, hitsAir))
            Weapon.MORTAR -> projectiles.add(Projectile(ShotKind.ORB, mx, my, target, t.type.shotSpeed, t.damage, t.splash, t.type.color, hitsAir))
            Weapon.FROST -> frost(t)
            Weapon.ARC -> arc(t, target)
            Weapon.RAIL -> rail(t)
        }
    }

    /** A shell from the player's tank at ([x], [y]), barrel facing [aim]: a small splash, ground only. */
    fun fireShell(x: Float, y: Float, aim: Float, target: Enemy, damage: Float) {
        val mx = x + cos(aim) * 0.4f
        val my = y + sin(aim) * 0.4f
        projectiles.add(Projectile(ShotKind.ORB, mx, my, target, Balance.TANK_SHOT_SPEED, damage, Balance.TANK_SPLASH, Balance.TANK_COLOR, false))
    }

    private fun pickTarget(t: Tower): Enemy? {
        val r2 = t.range * t.range
        var best: Enemy? = null
        var bestScore = 0f
        for (e in enemies) {
            if (!e.alive || e.dist <= 0.3f) continue
            val d2 = sq(e.x - t.x) + sq(e.y - t.y)
            if (d2 > r2 || !canHit(t, e)) continue
            val score = when (t.mode) {
                TargetMode.FIRST -> e.progress
                TargetMode.STRONG -> e.hp + e.shield + e.barrier
                TargetMode.CLOSE -> -d2
            }
            if (best == null || score > bestScore) {
                best = e
                bestScore = score
            }
        }
        return best
    }

    /** Slows and damages everything in range. */
    private fun frost(t: Tower) {
        val r2 = t.range * t.range
        for (e in enemies) {
            if (!e.alive || !canHit(t, e) || sq(e.x - t.x) + sq(e.y - t.y) > r2) continue
            e.slow(t.slowFactor, Balance.SLOW_SECONDS)
            damage(e, t.damage, false, t)
        }
        feedback.fx(FxKind.RING, t.x, t.y, t.type.color, 0.45f, size = t.range)
    }

    /** Lightning that jumps to the nearest unhit enemy, weakening with each jump. */
    private fun arc(t: Tower, first: Enemy) {
        val hit = ArrayList<Enemy>()
        val pts = ArrayList<Float>()
        pts.add(t.x); pts.add(t.y - 0.2f)
        var cur: Enemy? = first
        var dmg = t.damage
        repeat(t.chainJumps + 1) {
            val e = cur ?: return@repeat
            pts.add(e.x); pts.add(e.y)
            hit.add(e)
            damage(e, dmg, false, t)
            dmg *= Balance.CHAIN_FALLOFF
            var next: Enemy? = null
            var nd = Balance.CHAIN_REACH * Balance.CHAIN_REACH
            for (o in enemies) {
                if (!o.alive || !canHit(t, o) || o in hit) continue
                val d2 = sq(o.x - e.x) + sq(o.y - e.y)
                if (d2 < nd) { nd = d2; next = o }
            }
            cur = next
        }
        feedback.fx(FxKind.ARC, t.x, t.y, t.type.color, 0.18f, pts.toFloatArray())
    }

    /** A beam along the tower's aim that hits every enemy on the line, ignoring armor. */
    private fun rail(t: Tower) {
        val dx = cos(t.angle)
        val dy = sin(t.angle)
        val len = t.range
        for (e in enemies) {
            if (!e.alive || !canHit(t, e)) continue
            val px = e.x - t.x
            val py = e.y - t.y
            val along = px * dx + py * dy
            if (along < 0f || along > len) continue
            val perp = abs(px * dy - py * dx)
            if (perp <= Balance.RAIL_WIDTH + e.radius) damage(e, t.damage, true, t)
        }
        feedback.fx(FxKind.BEAM, t.x, t.y, t.type.color, 0.3f, floatArrayOf(t.x + dx * 0.4f, t.y + dy * 0.4f, t.x + dx * len, t.y + dy * len))
    }

    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            val target = p.target
            if (target != null && target.alive) {
                p.tx = target.x; p.ty = target.y
            } else {
                p.target = null
                if (p.kind == ShotKind.BOLT) { p.done = true; continue }
            }
            val dx = p.tx - p.x
            val dy = p.ty - p.y
            val d = hypot(dx, dy)
            val step = p.speed * dt
            if (d <= step + 0.05f) {
                p.x = p.tx; p.y = p.ty
                p.done = true
                impact(p)
            } else {
                p.x += dx / d * step
                p.y += dy / d * step
            }
        }
        projectiles.removeAll { it.done }
    }

    private fun impact(p: Projectile) {
        when (p.kind) {
            ShotKind.BOLT -> p.target?.let { damage(it, p.damage, false, null) }
            ShotKind.ORB -> {
                val r2 = p.splash * p.splash
                for (e in enemies) {
                    if (!e.alive || (e.type.layer == Layer.AIR && !p.hitsAir)) continue
                    if (sq(e.x - p.x) + sq(e.y - p.y) <= r2) damage(e, p.damage, false, null)
                }
                feedback.fx(FxKind.BLAST, p.x, p.y, p.color, 0.35f, size = p.splash)
                feedback.sound(Sfx.IMPACT, p.x, p.y)
            }
        }
    }

    private fun damage(e: Enemy, amount: Float, pierce: Boolean, by: Tower?) {
        if (!e.hit(amount, pierce)) return
        if (by != null) by.kills++
        val bounty = Balance.bounty(e.type.bounty, game.wave)
        game.credits += bounty
        feedback.floater("+$bounty", e.x, e.y - 0.2f)
        val big = e.type.bigDeath
        feedback.sound(if (big) Sfx.EXPLODE_BIG else Sfx.EXPLODE, e.x, e.y)
        feedback.debris(e.x, e.y, e.type.color, if (big) 40 else 10)
        feedback.fx(FxKind.BLAST, e.x, e.y, e.type.color, 0.3f, size = e.radius * 2f)
    }

    private fun sq(v: Float) = v * v
}
