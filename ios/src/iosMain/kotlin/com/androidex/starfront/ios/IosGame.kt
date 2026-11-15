package com.androidex.starfront.ios

import com.androidex.starfront.GameScreen
import com.androidex.starfront.game.Game
import com.androidex.starfront.gfx.TextMetrics
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UIScreen

/**
 * The iOS counterpart of the Android MainActivity: saves in NSUserDefaults (same keys), sound,
 * haptics, and pausing and saving when the app leaves the foreground. [setVertical] asks the
 * Swift side to lock the screen to portrait (true) or landscape (false).
 */
class IosGame(private val setVertical: (Boolean) -> Unit) : GameScreen.Host {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val sound = IosSound()
    private val observers = ArrayList<Any>()
    val screen: GameScreen

    init {
        TextMetrics.measure = SkiaCanvas.measurer()
        val game = defaults.stringForKey(KEY_SAVE)?.let { Game.restore(it) } ?: Game()
        screen = GameScreen(game, this, UIScreen.mainScreen.scale.toFloat())
        screen.soundSink = sound::play
        screen.muted = defaults.boolForKey(KEY_MUTED)
        setVertical(defaults.boolForKey(KEY_VERTICAL))
        observe(UIApplicationWillResignActiveNotification) {
            screen.running = false
            screen.pause()
            onSave(screen.saveState())
        }
        observe(UIApplicationDidBecomeActiveNotification) { screen.running = true }
        screen.running = true
    }

    private fun observe(name: String?, action: () -> Unit) {
        observers += NSNotificationCenter.defaultCenter.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ -> action() }
    }

    override fun onSave(state: String?) {
        if (state == null) defaults.removeObjectForKey(KEY_SAVE) else defaults.setObject(state, KEY_SAVE)
    }

    override fun onGameOver(wave: Int) {
        defaults.removeObjectForKey(KEY_SAVE)
        defaults.setInteger(maxOf(bestWave(), wave).toLong(), KEY_BEST)
    }

    override fun bestWave() = defaults.integerForKey(KEY_BEST).toInt()

    override fun onMuteChanged(muted: Boolean) = defaults.setBool(muted, KEY_MUTED)

    override fun onVerticalChanged(vertical: Boolean) {
        defaults.setBool(vertical, KEY_VERTICAL)
        setVertical(vertical)
    }

    override fun haptic(kind: GameScreen.Haptic) {
        val style = when (kind) {
            GameScreen.Haptic.TAP -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
            GameScreen.Haptic.CONFIRM -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
        }
        UIImpactFeedbackGenerator(style).impactOccurred()
    }

    private companion object {
        const val KEY_SAVE = "save"
        const val KEY_BEST = "best"
        const val KEY_MUTED = "muted"
        const val KEY_VERTICAL = "vertical"
    }
}
