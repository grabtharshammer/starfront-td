package com.androidex.starfront.game

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Moves enemies along their routes. Ground units walk the path, big ones in single file; air
 * units fly a weave straight from the warp gate to the core. Crossing a sector seam heading
 * east grants a barrier; reaching the end damages the core.
 */
class Movement(private val game: Game) {
    private val tmp = FloatArray(3)
    private val queue = ArrayList<Enemy>()

    private val world get() = game.world

    // Air route: a straight line from the warp gate to the core, with a weave on top.
    private val airStartX get() = world.minCol - 1.5f
    private val airLength get() = world.coreCol + 0.5f - airStartX

    private fun routeLength(e: Enemy) = when (e.type.layer) {
        Layer.GROUND -> world.pathLength
        Layer.AIR -> airLength
    }

    /** Places a newly spawned enemy at the start of its route. */
    fun launch(e: Enemy) {
        when (e.type.layer) {
            Layer.GROUND -> {
                world.positionAt(0f, tmp)
                e.x = tmp[0]; e.y = tmp[1]; e.heading = tmp[2]
            }
            Layer.AIR -> {
                val rng = game.rng
                e.waveAmp = 1.8f + rng.nextFloat() * 1.2f
                e.waveFreq = 0.35f + rng.nextFloat() * 0.2f
                e.wavePhase = rng.nextFloat() * 6.2832f
                airPoint(e, 0f, tmp)
                e.x = tmp[0]; e.y = tmp[1]
            }
        }
    }

    fun update(dt: Float) {
        queueBigGroundUnits()
        for (e in game.enemies) {
            if (!e.alive) continue
            e.sinceHit += dt
            if (e.slowTimer > 0f) {
                e.slowTimer -= dt
                if (e.slowTimer <= 0f) e.slowFactor = 1f
            }
            if (e.maxShield > 0f) {
                e.shieldDelay -= dt
                if (e.shieldDelay <= 0f) e.shield = minOf(e.maxShield, e.shield + e.maxShield * Balance.SHIELD_REGEN * dt)
            }
            val length = routeLength(e)
            e.dist = max(e.dist, minOf(e.dist + e.type.speed * e.slowFactor * dt, e.limit))
            e.progress = e.dist / length
            if (e.dist >= length) {
                game.coreHit(e)
                continue
            }
            place(e)
            checkSeam(e)
        }
    }

    /** Big ground units can't close within touching distance of the one ahead. */
    private fun queueBigGroundUnits() {
        queue.clear()
        for (e in game.enemies) {
            e.limit = Float.MAX_VALUE
            if (e.alive && e.type.layer == Layer.GROUND && e.radius >= Balance.QUEUE_RADIUS) queue.add(e)
        }
        queue.sortByDescending { it.dist }
        for (i in 1 until queue.size) {
            val ahead = queue[i - 1]
            val e = queue[i]
            e.limit = ahead.dist - (ahead.radius + e.radius) * Balance.QUEUE_GAP
        }
    }

    private fun place(e: Enemy) {
        when (e.type.layer) {
            Layer.GROUND -> {
                world.positionAt(e.dist, tmp)
                e.heading = tmp[2]
            }
            Layer.AIR -> {
                airPoint(e, e.dist + 0.05f, tmp)
                val ax = tmp[0]
                val ay = tmp[1]
                airPoint(e, e.dist, tmp)
                e.heading = atan2(ay - tmp[1], ax - tmp[0])
            }
        }
        e.x = tmp[0]; e.y = tmp[1]
    }

    private fun checkSeam(e: Enemy) {
        val sector = World.sectionIndexAt(floor(e.x).toInt())
        if (sector >= e.sector) return
        // Crossed a seam heading east: pick up a barrier.
        e.sector = sector
        e.addBarrier(e.maxHp * Balance.SEAM_BARRIER)
        game.feedback.sound(Sfx.BARRIER, e.x, e.y)
        game.feedback.fx(FxKind.RING, e.x, e.y, BARRIER_COLOR, 0.5f, size = e.radius * 3f)
    }

    private fun airPoint(e: Enemy, d: Float, out: FloatArray) {
        val u = (d / airLength).coerceIn(0f, 1f)
        val y0 = world.frontier.entryRow + 0.5f
        val y1 = world.coreRow + 0.5f
        // Weave is widest mid-flight and pinches in at the gate and the core.
        val envelope = sin(Math.PI.toFloat() * u).pow(0.6f)
        out[0] = airStartX + d
        out[1] = (y0 + (y1 - y0) * u + e.waveAmp * envelope * sin(d * e.waveFreq + e.wavePhase)).coerceIn(0.4f, ROWS - 0.4f)
    }
}
