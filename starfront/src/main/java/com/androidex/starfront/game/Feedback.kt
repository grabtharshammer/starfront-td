package com.androidex.starfront.game

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the simulation reports outward for presentation: short-lived visuals, floating numbers,
 * sounds and game events. The view reads and drains these; gameplay never reads them back.
 */
class Feedback(private val rng: Random) {
    val effects = ArrayList<Fx>()
    val particles = ArrayList<Particle>()
    val floaters = ArrayList<Floater>()
    val events = ArrayDeque<GameEvent>()
    /** Sounds requested since the view last drained them (capped so headless runs don't pile up). */
    val sounds = ArrayList<SoundEvent>()

    fun event(e: GameEvent) {
        events.add(e)
    }

    fun message(text: String) = event(GameEvent.Message(text))

    fun sound(sfx: Sfx, x: Float = Float.NaN, y: Float = Float.NaN) {
        if (sounds.size < 64) sounds.add(SoundEvent(sfx, x, y))
    }

    fun fx(kind: FxKind, x: Float, y: Float, color: Int, life: Float, points: FloatArray? = null, size: Float = 1f) {
        effects.add(Fx(kind, x, y, color, life, points, size))
    }

    fun floater(text: String, x: Float, y: Float, color: Int = CREDIT_COLOR) {
        floaters.add(Floater(text, x, y, color))
    }

    /** A spray of debris from a destroyed enemy. */
    fun debris(x: Float, y: Float, color: Int, count: Int) {
        repeat(count) {
            val a = rng.nextFloat() * 6.2832f
            val s = 0.6f + rng.nextFloat() * 2.2f
            particles.add(Particle(x, y, cos(a) * s, sin(a) * s, 0.4f + rng.nextFloat() * 0.4f, color, 0.05f + rng.nextFloat() * 0.06f))
        }
    }

    fun update(dt: Float) {
        for (f in effects) f.t += dt
        effects.removeAll { it.t >= it.life }
        for (p in particles) {
            p.t += dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.92f
            p.vy *= 0.92f
        }
        particles.removeAll { it.t >= it.life }
        for (f in floaters) {
            f.t += dt
            f.y -= 0.7f * dt
        }
        floaters.removeAll { it.t >= 0.9f }
    }

    companion object {
        const val CREDIT_COLOR = 0xFFFFD740.toInt()
    }
}
