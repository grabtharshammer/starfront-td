package com.androidex.starfront.audio

import com.androidex.starfront.game.Sfx
import com.androidex.starfront.game.SoundEvent
import kotlin.math.hypot
import kotlin.math.min

/** One sound to play with per-channel volumes in 0..1. */
class Play(val sfx: Sfx, val left: Float, val right: Float)

/**
 * Turns the game's sound requests into what the player should hear: only things inside the
 * visible part of the field, loudest at the centre of the screen and panned left/right, with
 * at most one play per effect per batch and a minimum gap between repeats.
 */
class Spatializer {
    private val lastPlayed = DoubleArray(Sfx.entries.size) { -1.0 }

    /**
     * [left], [top], [right], [bottom] are the visible world rectangle in cells; [now] is real
     * time in seconds.
     */
    fun mix(events: List<SoundEvent>, left: Float, top: Float, right: Float, bottom: Float, now: Double): List<Play> {
        val best = arrayOfNulls<Play>(Sfx.entries.size)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val hw = (right - left) / 2
        val hh = (bottom - top) / 2
        for (e in events) {
            val play = if (!e.positional) {
                Play(e.sfx, e.sfx.volume, e.sfx.volume)
            } else {
                if (e.x < left || e.x > right || e.y < top || e.y > bottom) continue
                val nx = (e.x - cx) / hw
                val ny = (e.y - cy) / hh
                val gain = e.sfx.volume * (1f - 0.8f * min(1f, hypot(nx, ny * 0.8f)))
                val pan = nx * 0.8f
                Play(e.sfx, gain * min(1f, 1f - pan), gain * min(1f, 1f + pan))
            }
            val cur = best[e.sfx.ordinal]
            if (cur == null || play.left + play.right > cur.left + cur.right) best[e.sfx.ordinal] = play
        }
        val out = ArrayList<Play>()
        for (p in best) {
            if (p == null) continue
            val i = p.sfx.ordinal
            if (lastPlayed[i] >= 0 && now - lastPlayed[i] < minGap(p.sfx)) continue
            lastPlayed[i] = now
            out.add(p)
        }
        return out
    }

    private fun minGap(sfx: Sfx): Double = when (sfx) {
        Sfx.PULSE, Sfx.EXPLODE, Sfx.IMPACT -> 0.06
        Sfx.PLASMA, Sfx.CRYO, Sfx.TESLA, Sfx.BARRIER -> 0.09
        Sfx.RAIL, Sfx.CORE_HIT -> 0.12
        else -> 0.0
    }
}
