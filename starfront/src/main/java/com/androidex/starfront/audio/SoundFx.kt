package com.androidex.starfront.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.androidex.starfront.game.Sfx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Plays the synthesized effects through a SoundPool. Sounds are rendered to WAV files in the
 * cache on a background thread at startup; any requested before they finish loading are skipped.
 */
class SoundFx(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val pending = ConcurrentHashMap<Int, Sfx>()
    private val ready = ConcurrentHashMap<Sfx, Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            // The lock waits out a load() whose id hasn't been recorded yet.
            val sfx = synchronized(pending) { pending.remove(sampleId) }
            if (status == 0 && sfx != null) ready[sfx] = sampleId
        }
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        thread(name = "sfx-synth", isDaemon = true) {
            for (sfx in Sfx.entries) {
                val file = File(dir, "${sfx.name.lowercase()}.wav")
                file.writeBytes(Synth.wav(Synth.render(sfx)))
                synchronized(pending) { pending[pool.load(file.path, 1)] = sfx }
            }
        }
    }

    fun play(p: Play) {
        val id = ready[p.sfx] ?: return
        // A little pitch variety keeps rapid-fire effects from sounding mechanical.
        val rate = if (p.sfx.volume < 0.6f) 0.94f + Random.nextFloat() * 0.12f else 1f
        pool.play(id, p.left, p.right, if (p.sfx.volume >= 0.8f) 1 else 0, 0, rate)
    }

    fun pause() = pool.autoPause()

    fun resume() = pool.autoResume()

    fun release() = pool.release()
}
