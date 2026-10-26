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
 * at most one play per effect per frame and a minimum gap between repeats.
 *
 * Sounds are [offer]ed one at a time as the game makes them and only the loudest candidate per
 * effect is kept, so a busy frame never crowds out what's on screen. [finish] hands over the
 * frame's plays and starts the next frame.
 */
class Spatializer {
    private val lastPlayed = DoubleArray(Sfx.entries.size) { -1.0 }
    private val best = arrayOfNulls<Play>(Sfx.entries.size)
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f

    /** The visible world rectangle in cells, used for sounds offered from now on. */
    fun setView(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun offer(e: SoundEvent) {
        val play = if (!e.positional) {
            Play(e.sfx, e.sfx.volume, e.sfx.volume)
        } else {
            if (e.x < left || e.x > right || e.y < top || e.y > bottom) return
            val cx = (left + right) / 2
            val cy = (top + bottom) / 2
            val nx = (e.x - cx) / ((right - left) / 2)
            val ny = (e.y - cy) / ((bottom - top) / 2)
            val gain = e.sfx.volume * (1f - 0.8f * min(1f, hypot(nx, ny * 0.8f)))
            val pan = nx * 0.8f
            Play(e.sfx, gain * min(1f, 1f - pan), gain * min(1f, 1f + pan))
        }
        val cur = best[e.sfx.ordinal]
        if (cur == null || play.left + play.right > cur.left + cur.right) best[e.sfx.ordinal] = play
    }

    /** The plays for this frame ([now] is real time in seconds), dropping too-quick repeats. */
    fun finish(now: Double): List<Play> {
        val out = ArrayList<Play>()
        for (i in best.indices) {
            val p = best[i] ?: continue
            best[i] = null
            if (lastPlayed[i] >= 0 && now - lastPlayed[i] < minGap(p.sfx)) continue
            lastPlayed[i] = now
            out.add(p)
        }
        return out
    }

    /** Offers [events] against the given view and finishes the frame in one go. */
    fun mix(events: List<SoundEvent>, left: Float, top: Float, right: Float, bottom: Float, now: Double): List<Play> {
        setView(left, top, right, bottom)
        for (e in events) offer(e)
        return finish(now)
    }

    private fun minGap(sfx: Sfx): Double = when (sfx) {
        Sfx.PULSE, Sfx.EXPLODE, Sfx.IMPACT -> 0.06
        Sfx.PLASMA, Sfx.CRYO, Sfx.TESLA, Sfx.BARRIER -> 0.09
        Sfx.RAIL, Sfx.CORE_HIT -> 0.12
        else -> 0.0
    }
}
