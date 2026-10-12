package org.pockettts.android.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/** Quantized feedback, not pixel-by-pixel vibration; shared across all foreground controls. */
internal class FeedbackGate {
    private var lastTick = Long.MIN_VALUE
    fun allow(now: Long): Boolean {
        if (lastTick != Long.MIN_VALUE && now - lastTick < 90) return false
        lastTick = now
        return true
    }
    companion object {
        fun changed(old: Float, value: Float, step: Float, min: Float, max: Float): Boolean {
            if (!old.isFinite() || !value.isFinite() || old == value || step <= 0) return false
            return (value == min && old != min) || (value == max && old != max) ||
                (old / step).roundToInt() != (value / step).roundToInt()
        }
        fun destination(x: Float, y: Float, width: Int, height: Int, rtl: Boolean = false): Int? {
            if (width <= 0 || height <= 0 || x < 0 || x >= width || y < 0 || y >= height) return null
            val index = (x / width * 3).toInt().coerceIn(0, 2)
            return if (rtl) 2 - index else index
        }
    }
}

internal class AppHaptics(private val activity: Activity, private val enabled: () -> Boolean) {
    private val gate = FeedbackGate()
    private val confirmations = FeedbackGate()
    private val surfaces = mutableListOf<View>()
    fun attach(view: View) { surfaces += view }
    fun detach(view: View) { surfaces.remove(view) }
    fun tick() = emit(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = emit(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK, completion = true)
    fun value(old: Float, value: Float, step: Float, min: Float, max: Float) {
        if (FeedbackGate.changed(old, value, step, min, max)) tick()
    }
    private fun emit(kind: Int, completion: Boolean = false) {
        if (!enabled() || activity.isFinishing || activity.isDestroyed) return
        val host = surfaces.lastOrNull { it.isAttachedToWindow && it.hasWindowFocus() }
            ?: activity.window.decorView.takeIf { it.hasWindowFocus() } ?: return
        if (Settings.System.getInt(activity.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        // A hover tick must not swallow the distinct successful release confirmation.
        if (!(if (completion) confirmations else gate).allow(SystemClock.uptimeMillis())) return
        // Never use IGNORE_* flags or a raw vibrator; native user/device preferences win.
        host.performHapticFeedback(kind)
    }
}

private fun Context.hapticActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.hapticActivity()
    else -> null
}

/** Sheets have their own focused window; detach on disposal so no View is retained. */
@Composable
internal fun HapticSurface() {
    val view = LocalView.current
    val feedback = view.context.hapticActivity()?.haptics
    DisposableEffect(view, feedback) {
        feedback?.attach(view)
        onDispose { feedback?.detach(view) }
    }
}

/** Observe confirmed transport states without changing the Reader's transport or editor. */
internal class ReaderHapticObserver(private val tick: () -> Unit) {
    private var initialized = false
    private var lastState = ReaderPlaybackService.State.IDLE
    private var lastTransport = ReaderPlaybackService.State.IDLE
    fun reset() { initialized = false }
    fun update(state: ReaderPlaybackService.State) {
        if (!initialized) {
            initialized = true; lastState = state; lastTransport = state
            return
        }
        if (state == ReaderPlaybackService.State.PLAYING && lastTransport != state) tick()
        else if (state == ReaderPlaybackService.State.PAUSED && lastState != state) tick()
        else if (state == ReaderPlaybackService.State.STOPPED && lastState in listOf(
                ReaderPlaybackService.State.PLAYING, ReaderPlaybackService.State.PAUSED, ReaderPlaybackService.State.LOADING)) tick()
        if (state != ReaderPlaybackService.State.LOADING) lastTransport = state
        lastState = state
    }
}
