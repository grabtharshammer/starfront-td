package com.androidex.starfront.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.window.ComposeUIViewController
import com.androidex.starfront.GameScreen
import platform.UIKit.UIViewController

/**
 * Entry point for Swift: the game in a view controller. [setVertical] is called with the
 * orientation the game wants (true = portrait), at start and when the player switches it.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(setVertical: (Boolean) -> Unit): UIViewController {
    val game = IosGame(setVertical)
    return ComposeUIViewController { GameHost(game.screen) }
}

@Composable
private fun GameHost(screen: GameScreen) {
    val canvas = remember { SkiaCanvas() }
    var frameTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frameTime = it }
    }
    val insets = WindowInsets.safeDrawing
    Canvas(Modifier.fillMaxSize().pointerInput(screen) { gestures(screen) }) {
        @Suppress("UNUSED_EXPRESSION") frameTime // Redraw every display frame.
        val left = insets.getLeft(this, layoutDirection).toFloat()
        val right = insets.getRight(this, layoutDirection).toFloat()
        screen.cutL = left
        screen.cutR = right
        screen.gestureL = left
        screen.gestureR = right
        screen.safeT = insets.getTop(this).toFloat()
        screen.safeB = insets.getBottom(this).toFloat()
        screen.resize(size.width.toInt(), size.height.toInt())
        drawIntoCanvas {
            canvas.target = it.nativeCanvas
            screen.frame(canvas)
        }
    }
}

/**
 * Turns touches into the gestures Android's GestureDetector and ScaleGestureDetector report:
 * down, then a tap, a drag (ending in a fling), or with two fingers a pinch.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.gestures(screen: GameScreen) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        screen.down(first.position.x, first.position.y)
        val velocity = VelocityTracker()
        velocity.addPosition(first.uptimeMillis, first.position)
        val slop = viewConfiguration.touchSlop
        var dragging = false
        var pinched = false
        var last = first.position
        var lastSpan = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            event.changes.forEach { it.consume() }
            if (pressed.isEmpty()) {
                val up = event.changes.first().position
                if (!dragging && !pinched) screen.tap(up.x, up.y)
                else if (dragging && !pinched) velocity.calculateVelocity().let { screen.fling(it.x, it.y) }
                break
            }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val span = (a - b).getDistance()
                val focus = (a + b) / 2f
                if (pinched && lastSpan > 0f) screen.scale(span / lastSpan, focus.x, focus.y)
                pinched = true
                lastSpan = span
                continue
            }
            // One finger; after a pinch the remaining finger does nothing, as on Android.
            if (pinched) continue
            val p = pressed[0]
            velocity.addPosition(p.uptimeMillis, p.position)
            if (!dragging && (p.position - first.position).getDistance() > slop) {
                dragging = true
                last = first.position
            }
            if (dragging) {
                val d: Offset = p.position - last
                screen.scroll(-d.x, -d.y, p.position.x)
                last = p.position
            }
        }
    }
}
