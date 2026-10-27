package com.androidex.starfront.game

import kotlin.math.atan2
import kotlin.math.hypot

/** The player's tank once launched. It drives the enemy route backwards, from the core toward the warp gate. */
class Tank(val maxHp: Float, val damage: Float) {
    var hp = maxHp
    /** Distance along the enemy route; it counts down. Past the route's end it is still rolling out of the bay. */
    var dist = 0f
    var x = 0f
    var y = 0f
    var heading = 0f
    /** Turret direction, radians. */
    var aim = 0f
    var cooldown = 0f
    /** Seconds since the last shot and the last hit taken, for drawing. */
    var sinceShot = 10f
    var sinceHit = 10f
    /** True while ground units are pressed up against it. */
    var blocked = false
    val radius get() = Balance.TANK_RADIUS
}

/**
 * The tank bay beside the core. Each sector opened earns a tank; one at a time can be launched
 * during a wave. It shoots ground units as it meets them, and they can't get past it until they
 * destroy it. It's spent when destroyed, when it reaches the warp gate, or when the wave ends.
 */
class TankBay(private val game: Game) {
    /** Tanks parked and ready. */
    var charges = 0
    var tank: Tank? = null
        private set

    private val tmp = FloatArray(3)
    private val world get() = game.world

    // Parking spot, just south of the core and outside the grid.
    val parkX get() = world.coreCol + 0.5f
    val parkY get() = world.coreRow + 0.5f + Balance.TANK_PARK_OFFSET

    val canLaunch get() = charges > 0 && tank == null && game.phase == Phase.WAVE

    /** Whether a tap at world point ([wx], [wy]) lands on the parked tank. */
    fun isAtBay(wx: Float, wy: Float) = charges > 0 && hypot(wx - parkX, wy - parkY) < 0.75f

    fun launch(): Boolean {
        if (!canLaunch) return false
        charges--
        val strength = Balance.hpScale(game.wave) * Balance.sectorScale(world.sections.size)
        val t = Tank(Balance.TANK_HP * strength, Balance.TANK_DAMAGE * strength)
        t.dist = world.pathLength + Balance.TANK_PARK_OFFSET
        tank = t
        place(t)
        game.feedback.sound(Sfx.TANK_LAUNCH)
        game.feedback.message("TANK LAUNCHED")
        return true
    }

    fun update(dt: Float) {
        val t = tank ?: return
        t.sinceShot += dt
        t.sinceHit += dt
        // The nearest ground unit in front stops it; units pressed against it wear it down.
        var front: Enemy? = null
        for (e in game.enemies) {
            if (!e.alive || e.type.layer != Layer.GROUND || e.dist > t.dist) continue
            if (front == null || e.dist > front.dist) front = e
            val gap = t.dist - e.dist - (t.radius + e.radius)
            if (gap < Balance.TANK_CONTACT) {
                t.hp -= e.maxHp * Balance.TANK_CONTACT_DPS * dt
                t.sinceHit = 0f
            }
        }
        val stop = front?.let { it.dist + (t.radius + it.radius) * Balance.STACK_GAP } ?: Float.NEGATIVE_INFINITY
        val next = t.dist - Balance.TANK_SPEED * dt
        t.blocked = next < stop
        t.dist = maxOf(next, minOf(stop, t.dist))
        place(t)
        if (t.hp <= 0f) {
            destroyed(t)
            return
        }
        if (t.dist <= 0f) {
            tank = null
            game.feedback.message("TANK REACHED THE WARP GATE")
            game.feedback.fx(FxKind.RING, t.x, t.y, Balance.TANK_COLOR, 0.6f, size = 1.2f)
            return
        }
        shoot(t, dt)
    }

    /** The wave is over: whatever is left of the tank is spent. */
    fun clear() {
        tank = null
    }

    private fun shoot(t: Tank, dt: Float) {
        t.cooldown -= dt
        var target: Enemy? = null
        var best = Balance.TANK_RANGE * Balance.TANK_RANGE
        for (e in game.enemies) {
            if (!e.alive || e.type.layer != Layer.GROUND) continue
            val d2 = (e.x - t.x) * (e.x - t.x) + (e.y - t.y) * (e.y - t.y)
            if (d2 < best) { best = d2; target = e }
        }
        if (target == null) {
            t.aim = t.heading
            return
        }
        t.aim = atan2(target.y - t.y, target.x - t.x)
        if (t.cooldown > 0f) return
        t.cooldown = 1f / Balance.TANK_RATE
        t.sinceShot = 0f
        game.combat.fireShell(t.x, t.y, t.aim, target, t.damage)
        game.feedback.sound(Sfx.TANK_FIRE, t.x, t.y)
    }

    private fun destroyed(t: Tank) {
        tank = null
        game.feedback.message("TANK DESTROYED")
        game.feedback.sound(Sfx.EXPLODE_BIG, t.x, t.y)
        game.feedback.debris(t.x, t.y, Balance.TANK_COLOR, 40)
        game.feedback.fx(FxKind.BLAST, t.x, t.y, Balance.TANK_COLOR, 0.5f, size = 1.2f)
    }

    /** Positions the tank: rolling north out of the bay to the core, then west along the route. */
    private fun place(t: Tank) {
        val end = world.pathLength
        if (t.dist >= end) {
            val u = (t.dist - end) / Balance.TANK_PARK_OFFSET
            t.x = parkX
            t.y = world.coreRow + 0.5f + Balance.TANK_PARK_OFFSET * u
            t.heading = -Math.PI.toFloat() / 2
        } else {
            world.positionAt(t.dist, tmp)
            t.x = tmp[0]; t.y = tmp[1]
            // The route heads east; the tank faces the other way.
            t.heading = tmp[2] + Math.PI.toFloat()
        }
    }
}
