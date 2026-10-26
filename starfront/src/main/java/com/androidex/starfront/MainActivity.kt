package com.androidex.starfront

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import com.androidex.starfront.audio.SoundFx
import com.androidex.starfront.game.Game

class MainActivity : Activity(), GameView.Listener {

    private lateinit var view: GameView
    private lateinit var sfx: SoundFx
    private val prefs by lazy { getSharedPreferences("starfront", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setDecorFitsSystemWindows(false)

        val game = prefs.getString(KEY_SAVE, null)?.let { Game.restore(it) } ?: Game()
        sfx = SoundFx(this)
        view = GameView(this, game, this)
        view.soundSink = sfx::play
        view.muted = prefs.getBoolean(KEY_MUTED, false)
        setContentView(view)

        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!view.onBack()) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        view.running = true
        sfx.resume()
    }

    override fun onPause() {
        super.onPause()
        view.running = false
        view.pause()
        sfx.pause()
        onSave(view.saveState())
    }

    override fun onSave(state: String?) {
        prefs.edit().apply { if (state == null) remove(KEY_SAVE) else putString(KEY_SAVE, state) }.apply()
    }

    override fun onGameOver(wave: Int) {
        val best = maxOf(bestWave(), wave)
        prefs.edit().remove(KEY_SAVE).putInt(KEY_BEST, best).apply()
    }

    override fun bestWave() = prefs.getInt(KEY_BEST, 0)

    override fun onMuteChanged(muted: Boolean) {
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }

    private companion object {
        const val KEY_SAVE = "save"
        const val KEY_BEST = "best"
        const val KEY_MUTED = "muted"
    }
}
