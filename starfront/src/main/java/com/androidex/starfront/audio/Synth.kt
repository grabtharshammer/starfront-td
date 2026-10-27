package com.androidex.starfront.audio

import com.androidex.starfront.game.Sfx
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates every sound effect procedurally, so the app ships no audio files.
 * Output is mono 16-bit PCM at [RATE].
 */
object Synth {
    const val RATE = 22050

    fun render(sfx: Sfx): FloatArray = when (sfx) {
        Sfx.PULSE -> tone(0.09f, 1800f, 500f, Wave.SQUARE, decay = 30f) * 0.5f
        Sfx.PLASMA -> mix(
            tone(0.28f, 240f, 55f, Wave.SINE, decay = 9f),
            noise(0.12f, cutoff = 0.15f, decay = 25f) * 0.5f,
        )
        Sfx.IMPACT -> mix(
            noise(0.22f, cutoff = 0.12f, decay = 16f),
            tone(0.18f, 120f, 40f, Wave.SINE, decay = 14f) * 0.8f,
        )
        Sfx.CRYO -> mix(
            tone(0.35f, 2100f, 1900f, Wave.SINE, decay = 7f, attack = 0.04f, tremolo = 28f) * 0.4f,
            tone(0.35f, 2750f, 2500f, Wave.SINE, decay = 7f, attack = 0.04f, tremolo = 33f) * 0.3f,
            noise(0.35f, cutoff = 0.6f, decay = 8f, attack = 0.03f) * 0.25f,
        )
        Sfx.TESLA -> mix(
            crackle(0.22f),
            tone(0.22f, 110f, 95f, Wave.SQUARE, decay = 10f) * 0.25f,
        )
        Sfx.RAIL -> concat(
            tone(0.07f, 400f, 3200f, Wave.SAW, decay = 0f, attack = 0.05f) * 0.35f,
            mix(
                noise(0.3f, cutoff = 0.5f, decay = 12f) * 0.7f,
                tone(0.3f, 900f, 150f, Wave.SAW, decay = 10f) * 0.4f,
            ),
        )
        Sfx.EXPLODE -> noise(0.38f, cutoff = 0.25f, decay = 9f, sweep = 0.03f)
        Sfx.EXPLODE_BIG -> mix(
            noise(1.3f, cutoff = 0.3f, decay = 3f, sweep = 0.02f),
            tone(1.0f, 90f, 28f, Wave.SINE, decay = 3f) * 0.9f,
        )
        Sfx.BARRIER -> mix(
            tone(0.3f, 620f, 1240f, Wave.SINE, decay = 8f) * 0.6f,
            tone(0.3f, 930f, 1860f, Wave.SINE, decay = 9f) * 0.35f,
        )
        Sfx.CORE_HIT -> mix(
            tone(0.45f, 160f, 90f, Wave.SQUARE, decay = 6f) * 0.45f,
            noise(0.3f, cutoff = 0.2f, decay = 10f) * 0.6f,
        )
        Sfx.BUILD -> notes(0.07f, 660f, 990f)
        Sfx.UPGRADE -> notes(0.07f, 523f, 659f, 784f, 1047f)
        Sfx.EFFICIENCY -> mix(
            notes(0.08f, 784f, 988f, 1175f, 1568f),
            delay(chord(0.9f, 392f, 494f, 587f, 784f) * 0.45f, 0.3f),
        )
        Sfx.SELL -> notes(0.08f, 880f, 587f)
        Sfx.DENIED -> tone(0.14f, 150f, 130f, Wave.SQUARE, decay = 12f) * 0.5f
        Sfx.WAVE_START -> mix(
            tone(0.75f, 110f, 165f, Wave.SAW, decay = 3f, attack = 0.08f, vibrato = 6f) * 0.45f,
            tone(0.75f, 55f, 82f, Wave.SQUARE, decay = 3f, attack = 0.08f) * 0.25f,
        )
        Sfx.AIR_RAID -> concat(
            tone(0.22f, 720f, 720f, Wave.SQUARE, decay = 1f, attack = 0.01f) * 0.4f,
            tone(0.22f, 540f, 540f, Wave.SQUARE, decay = 1f, attack = 0.01f) * 0.4f,
            tone(0.22f, 720f, 720f, Wave.SQUARE, decay = 1f, attack = 0.01f) * 0.4f,
            tone(0.3f, 540f, 540f, Wave.SQUARE, decay = 6f, attack = 0.01f) * 0.4f,
        )
        Sfx.WAVE_CLEAR -> notes(0.09f, 523f, 659f, 784f, 1047f, 1319f)
        Sfx.SECTOR_OPEN -> mix(
            noise(1.3f, cutoff = 0.05f, decay = 2f, attack = 0.6f, sweep = -0.4f) * 0.5f,
            delay(chord(1.0f, 262f, 330f, 392f, 523f) * 0.5f, 0.4f),
        )
        Sfx.GAME_OVER -> mix(
            tone(1.6f, 440f, 55f, Wave.SAW, decay = 1.8f) * 0.5f,
            tone(1.6f, 220f, 30f, Wave.SQUARE, decay = 1.8f) * 0.3f,
        )
        Sfx.TANK_FIRE -> mix(
            tone(0.2f, 170f, 60f, Wave.SQUARE, decay = 14f) * 0.35f,
            noise(0.18f, cutoff = 0.2f, decay = 18f) * 0.6f,
        )
        Sfx.TANK_LAUNCH -> mix(
            tone(0.9f, 55f, 110f, Wave.SAW, decay = 2.5f, attack = 0.1f) * 0.4f,
            delay(notes(0.08f, 392f, 523f, 659f) * 0.5f, 0.2f),
        )
    }

    /** Encodes samples in [-1, 1] as a 16-bit mono WAV file. */
    fun wav(samples: FloatArray): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(dataLen)
        for (s in samples) i16((s.coerceIn(-1f, 1f) * 32767).toInt())
        return out.toByteArray()
    }

    // ---- Building blocks ----

    enum class Wave { SINE, SQUARE, SAW }

    private val rng = Random(7)

    private fun frames(seconds: Float) = (seconds * RATE).toInt()

    /** Attack/decay envelope: linear fade-in over [attack] s, then exponential decay, with a short fade-out. */
    private fun env(i: Int, n: Int, decay: Float, attack: Float): Float {
        val t = i.toFloat() / RATE
        val a = if (attack > 0f) min(1f, t / attack) else 1f
        val d = exp(-decay * max(0f, t - attack))
        val tail = min(1f, (n - i) / (RATE * 0.01f))
        return a * d * tail
    }

    /** A pitch sweep from [f0] to [f1] Hz (exponential glide). */
    private fun tone(
        seconds: Float, f0: Float, f1: Float, wave: Wave, decay: Float,
        attack: Float = 0.002f, tremolo: Float = 0f, vibrato: Float = 0f,
    ): FloatArray {
        val n = frames(seconds)
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val u = i.toFloat() / n
            var f = f0 * Math.pow((f1 / f0).toDouble(), u.toDouble()).toFloat()
            if (vibrato > 0f) f *= 1f + 0.02f * sin(2 * PI * vibrato * i / RATE).toFloat()
            phase += f / RATE
            val p = phase - kotlin.math.floor(phase)
            var v = when (wave) {
                Wave.SINE -> sin(2 * PI * p).toFloat()
                Wave.SQUARE -> if (p < 0.5) 0.7f else -0.7f
                Wave.SAW -> (2 * p - 1).toFloat() * 0.8f
            }
            if (tremolo > 0f) v *= 0.6f + 0.4f * sin(2 * PI * tremolo * i / RATE).toFloat()
            out[i] = v * env(i, n, decay, attack)
        }
        return out
    }

    /** Low-passed white noise; [cutoff] in 0..1 is the one-pole filter coefficient, [sweep] changes it per second. */
    private fun noise(seconds: Float, cutoff: Float, decay: Float, attack: Float = 0.002f, sweep: Float = 0f): FloatArray {
        val n = frames(seconds)
        val out = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            val k = (cutoff + sweep * i / RATE).coerceIn(0.01f, 1f)
            y += k * ((rng.nextFloat() * 2 - 1) - y)
            out[i] = y * env(i, n, decay, attack) * (1.4f / kotlin.math.sqrt(k))
        }
        return normalize(out, 0.9f)
    }

    /** Electric crackle: noise gated by random short bursts. */
    private fun crackle(seconds: Float): FloatArray {
        val n = frames(seconds)
        val out = FloatArray(n)
        var gate = 0
        var amp = 0f
        for (i in 0 until n) {
            if (gate-- <= 0) {
                gate = (RATE * (0.004f + rng.nextFloat() * 0.018f)).toInt()
                amp = if (rng.nextFloat() < 0.6f) 0.4f + rng.nextFloat() * 0.6f else 0f
            }
            out[i] = (rng.nextFloat() * 2 - 1) * amp * env(i, n, 8f, 0f)
        }
        return out
    }

    /** Short notes played one after another. */
    private fun notes(each: Float, vararg freqs: Float): FloatArray {
        val step = frames(each)
        val out = FloatArray(step * freqs.size + frames(each * 2f))
        for ((k, f) in freqs.withIndex()) {
            val note = mix(tone(each * 2f, f, f, Wave.SQUARE, decay = 18f) * 0.3f, tone(each * 2f, f * 2, f * 2, Wave.SINE, decay = 22f) * 0.2f)
            for (i in note.indices) out[k * step + i] += note[i]
        }
        return normalize(out, 0.9f, onlyIfLouder = true)
    }

    private fun chord(seconds: Float, vararg freqs: Float): FloatArray =
        mix(*freqs.map { tone(seconds, it, it, Wave.SINE, decay = 2.5f, attack = 0.05f) * (1f / freqs.size) }.toTypedArray())

    private fun delay(a: FloatArray, seconds: Float): FloatArray = FloatArray(frames(seconds)) + a

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return normalize(out, 0.95f, onlyIfLouder = true)
    }

    private fun normalize(a: FloatArray, peak: Float, onlyIfLouder: Boolean = false): FloatArray {
        val m = a.maxOfOrNull { kotlin.math.abs(it) } ?: return a
        if (m <= 0f || (onlyIfLouder && m <= peak)) return a
        val k = peak / m
        for (i in a.indices) a[i] *= k
        return a
    }

    private operator fun FloatArray.times(k: Float): FloatArray = FloatArray(size) { this[it] * k }
}
