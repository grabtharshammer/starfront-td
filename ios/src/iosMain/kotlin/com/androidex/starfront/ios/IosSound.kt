package com.androidex.starfront.ios

import com.androidex.starfront.audio.Play
import com.androidex.starfront.audio.Synth
import com.androidex.starfront.game.Sfx
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.math.max

/**
 * Plays the synthesized effects with a few AVAudioPlayers per effect, so rapid repeats overlap
 * like the Android SoundPool. The WAVs are rendered in the background at startup; sounds
 * requested before then are skipped. Ambient audio: mixes with music, quiet with the mute switch.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSound {
    private var players: Map<Sfx, List<AVAudioPlayer>> = emptyMap()
    private val next = IntArray(Sfx.entries.size)

    init {
        AVAudioSession.sharedInstance().apply {
            setCategory(AVAudioSessionCategoryAmbient, null)
            setActive(true, null)
        }
        CoroutineScope(Dispatchers.Default).launch {
            val wavs = Sfx.entries.associateWith { Synth.wav(Synth.render(it)) }
            withContext(Dispatchers.Main) {
                players = wavs.mapValues { (_, wav) -> List(VOICES) { player(wav) }.filterNotNull() }
            }
        }
    }

    private fun player(wav: ByteArray): AVAudioPlayer? =
        runCatching { AVAudioPlayer(data = wav.toNSData(), error = null) }.getOrNull()?.apply { prepareToPlay() }

    fun play(p: Play) {
        val voices = players[p.sfx]
        if (voices.isNullOrEmpty()) return
        val i = next[p.sfx.ordinal]
        next[p.sfx.ordinal] = (i + 1) % voices.size
        val player = voices[i]
        val volume = max(p.left, p.right)
        player.volume = volume
        player.pan = if (volume > 0f) (p.right - p.left) / (p.right + p.left) else 0f
        player.currentTime = 0.0
        player.play()
    }

    private companion object {
        const val VOICES = 4
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}
