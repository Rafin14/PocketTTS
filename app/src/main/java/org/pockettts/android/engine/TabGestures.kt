package org.pockettts.android.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Header-only gesture zone: never intercept the native editor, list or audio controls. */
@Composable
internal fun Modifier.tabSwipeNavigation(app: MainActivity, enabled: Boolean): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val motion = LocalStudioMotion.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var delta by remember { mutableFloatStateOf(0f) }
    val tab by rememberUpdatedState(app.tab)
    val allowed by rememberUpdatedState(enabled)
    val modifier = Modifier.pointerInput(enabled, rtl, motion) {
        val velocity = VelocityTracker()
        var startTab = 0
        fun settle(releaseVelocity: Float = 0f) {
            val from = delta
            dragging = false
            scope.launch {
                offset.snapTo(from)
                if (motion) offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 650f), initialVelocity = releaseVelocity)
                else offset.snapTo(0f)
            }
        }
        detectHorizontalDragGestures(
            onDragStart = {
                startTab = tab; velocity.resetTracking()
                delta = offset.value; dragging = true
                scope.launch { offset.stop() }
            },
            onHorizontalDrag = { change, amount ->
                if (allowed) {
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    delta = (delta + amount).coerceIn(-size.width * .3f, size.width * .3f)
                }
            },
            onDragCancel = { settle() },
            onDragEnd = {
                val speed = velocity.calculateVelocity().x
                if (allowed && tab == startTab && (abs(delta) > size.width * .16f || abs(speed) > 650f)) {
                    val direction = if (abs(speed) > 650f) speed else delta
                    val next = startTab + (if ((direction < 0) != rtl) 1 else -1)
                    if (next in 0..2) { app.switchTab(next); app.haptics.tick() }
                }
                settle(speed)
            })
    }
    // Feedback stays in shared chrome; the Reader editor/layout is never moved or recreated.
    return then(modifier).graphicsLayer { translationX = if (motion) (if (dragging) delta else offset.value) * .18f else 0f }
}
