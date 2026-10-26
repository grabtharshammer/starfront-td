package com.androidex.starfront.audio

import com.androidex.starfront.game.Game
import com.androidex.starfront.game.Phase
import com.androidex.starfront.game.Sfx
import com.androidex.starfront.game.SoundEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioTest {

    @Test
    fun everyEffectRendersCleanly() {
        for (sfx in Sfx.entries) {
            val s = Synth.render(sfx)
            assertTrue("$sfx empty", s.size > Synth.RATE / 50)
            assertTrue("$sfx too long", s.size < Synth.RATE * 2)
            val peak = s.maxOf { abs(it) }
            assertTrue("$sfx clips: $peak", peak <= 1f)
            assertTrue("$sfx silent: $peak", peak > 0.1f)
            assertTrue("$sfx has NaN", s.none { it.isNaN() })
            // Ends quietly, so there's no click at the end.
            assertTrue("$sfx ends loud", abs(s.last()) < 0.05f)
            val wav = Synth.wav(s)
            assertEquals(44 + s.size * 2, wav.size)
            assertEquals("RIFF", String(wav, 0, 4))
            assertEquals("WAVE", String(wav, 8, 4))
        }
    }

    // A 20 x 10 cell view.
    private fun mix(sp: Spatializer, vararg e: SoundEvent, now: Double = 0.0) = sp.mix(e.toList(), 0f, 0f, 20f, 10f, now)

    @Test
    fun offScreenIsSilentAndCentreIsLoudest() {
        assertTrue(mix(Spatializer(), SoundEvent(Sfx.EXPLODE, -0.5f, 5f)).isEmpty())
        assertTrue(mix(Spatializer(), SoundEvent(Sfx.EXPLODE, 10f, 10.5f)).isEmpty())
        val centre = mix(Spatializer(), SoundEvent(Sfx.EXPLODE, 10f, 5f)).single()
        val edge = mix(Spatializer(), SoundEvent(Sfx.EXPLODE, 19f, 5f)).single()
        assertEquals(Sfx.EXPLODE.volume, centre.left, 1e-4f)
        assertEquals(centre.left, centre.right, 1e-4f)
        assertTrue(edge.left + edge.right < (centre.left + centre.right) * 0.5f)
        // Panned right.
        assertTrue(edge.right > edge.left * 3)
        // UI sounds ignore the camera.
        val ui = mix(Spatializer(), SoundEvent(Sfx.BUILD)).single()
        assertEquals(Sfx.BUILD.volume, ui.left, 0f)
    }

    @Test
    fun repeatsAreMergedAndThrottled() {
        val sp = Spatializer()
        val plays = mix(sp, SoundEvent(Sfx.PULSE, 2f, 5f), SoundEvent(Sfx.PULSE, 10f, 5f), SoundEvent(Sfx.RAIL, 10f, 5f))
        assertEquals(2, plays.size)
        // The louder (more central) pulse wins.
        assertEquals(Sfx.PULSE.volume, plays.first { it.sfx == Sfx.PULSE }.left, 1e-4f)
        assertTrue(mix(sp, SoundEvent(Sfx.PULSE, 10f, 5f), now = 0.01).isEmpty())
        assertEquals(1, mix(sp, SoundEvent(Sfx.PULSE, 10f, 5f), now = 0.2).size)
    }

    @Test
    fun gameRequestsSounds() {
        val g = Game(4)
        val heard = ArrayList<SoundEvent>()
        g.feedback.onSound = { heard.add(it) }
        g.startWave()
        assertTrue(heard.any { it.sfx == Sfx.WAVE_START && !it.positional })
        heard.clear()
        while (g.phase == Phase.WAVE) g.update(1f / 30)
        assertTrue(heard.any { it.sfx == Sfx.CORE_HIT && it.positional })
    }

    @Test
    fun busyFrameDoesNotCrowdOutWhatsOnScreen() {
        val sp = Spatializer()
        sp.setView(0f, 0f, 20f, 10f)
        // Hundreds of off-screen explosions first, then one in view.
        repeat(500) { sp.offer(SoundEvent(Sfx.EXPLODE, -50f - it, 5f)) }
        repeat(500) { sp.offer(SoundEvent(Sfx.PULSE, 100f + it, 5f)) }
        sp.offer(SoundEvent(Sfx.EXPLODE, 12f, 5f))
        val plays = sp.finish(0.0)
        assertEquals(listOf(Sfx.EXPLODE), plays.map { it.sfx })
        // The next frame starts empty.
        assertTrue(sp.finish(1.0).isEmpty())
    }

    @Test
    fun soundsWithoutAListenerAreDropped() {
        val g = Game(4)
        repeat(10_000) { g.sound(Sfx.PULSE, 1f, 1f) }
        g.startWave()
        while (g.phase == Phase.WAVE) g.update(1f / 30)
        // Nothing to assert beyond not accumulating: there is no buffer to grow.
        assertTrue(g.feedback.onSound == null)
    }
}
