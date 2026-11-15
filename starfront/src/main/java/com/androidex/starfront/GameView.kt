package com.androidex.starfront

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import com.androidex.starfront.game.Game
import com.androidex.starfront.gfx.TextMetrics

/**
 * Hosts the shared [GameScreen] in an Android View: forwards size, insets and touch gestures,
 * and redraws every display frame while [running].
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, game: Game, host: Host) : View(context) {

    /** The [GameScreen.Host] callbacks, minus haptics, which the view handles itself. */
    interface Host {
        fun onSave(state: String?)
        fun onGameOver(wave: Int)
        fun bestWave(): Int
        fun onMuteChanged(muted: Boolean)
        fun onVerticalChanged(vertical: Boolean)
    }

    init {
        TextMetrics.measure = AndroidCanvas.measurer()
    }

    val screen = GameScreen(game, object : GameScreen.Host {
        override fun onSave(state: String?) = host.onSave(state)
        override fun onGameOver(wave: Int) = host.onGameOver(wave)
        override fun bestWave() = host.bestWave()
        override fun onMuteChanged(muted: Boolean) = host.onMuteChanged(muted)
        override fun onVerticalChanged(vertical: Boolean) = host.onVerticalChanged(vertical)
        override fun haptic(kind: GameScreen.Haptic) {
            performHapticFeedback(
                when (kind) {
                    GameScreen.Haptic.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
                    GameScreen.Haptic.CONFIRM -> HapticFeedbackConstants.CONFIRM
                }
            )
        }
    }, resources.displayMetrics.density)

    private val canvas = AndroidCanvas()

    var running: Boolean
        get() = screen.running
        set(value) {
            screen.running = value
            if (value) postInvalidateOnAnimation()
        }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            screen.down(e.x, e.y); return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            screen.tap(e.x, e.y); return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            screen.scroll(dx, dy, e2.x); return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            screen.fling(vx, vy); return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            screen.scale(det.scaleFactor, det.focusX, det.focusY); return true
        }
    })

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemGestures())
            screen.gestureL = cut.left.toFloat()
            screen.gestureR = cut.right.toFloat()
            val notch = insets.getInsets(WindowInsets.Type.displayCutout())
            screen.cutL = notch.left.toFloat()
            screen.cutR = notch.right.toFloat()
            screen.safeT = notch.top.toFloat()
            insets
        }
    }

    fun pause() = screen.pause()
    fun onBack() = screen.onBack()
    fun saveState() = screen.saveState()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = screen.resize(w, h)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gesture.onTouchEvent(event)
        return true
    }

    override fun onDraw(c: Canvas) {
        canvas.target = c
        screen.frame(canvas)
        if (running) postInvalidateOnAnimation()
    }
}
