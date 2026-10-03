@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.pockettts.android.engine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun trimTime(ms: Int) = "%02d:%02d.%02d".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms / 10 % 100)

@Composable
internal fun TrimControls(info: WavSamples.Info, start: Int, end: Int, change: (Int, Int) -> Unit,
                          player: SamplePreview, position: Int, seek: (Int) -> Unit, enabled: Boolean, preview: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f)
    val selected = end - start
    val valid = selected in 3000..30000
    val duration = info.durationMs.coerceAtLeast(1)
    val waveLabel = stringResource(R.string.trim_waveform)
    val state = player.state
    val playhead = (if (state.key != null) start + state.positionMs else position).coerceIn(start, end)
    val currentSeek = rememberUpdatedState(seek)
    val canSeek = rememberUpdatedState(enabled && valid)
    val playheadColor = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.trim_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(112.dp)) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp)
                .semantics {
                    contentDescription = waveLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(playhead.toFloat(), start.toFloat()..end.toFloat())
                    if (!enabled || !valid) disabled()
                    setProgress { value -> if (enabled && valid) { seek(value.roundToInt().coerceIn(start, end)); true } else false }
                }.testTag("trim-waveform")
                .pointerInput(info, start, end) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (canSeek.value) {
                            fun move(x: Float) = currentSeek.value((x / size.width.coerceAtLeast(1) * duration).roundToInt().coerceIn(start, end))
                            down.consume(); move(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val point = event.changes.firstOrNull { it.id == down.id } ?: break
                                point.consume(); move(point.position.x)
                            } while (point.pressed)
                        }
                    }
                }) {
                val left = start.toFloat() / duration * size.width
                val right = end.toFloat() / duration * size.width
                drawRect(accent.copy(alpha = .09f), Offset(left, 0f), Size(right - left, size.height))
                val step = size.width / info.peaks.size
                info.peaks.forEachIndexed { index, peak ->
                    val x = (index + .5f) * step
                    val height = (sqrt(peak) * size.height * .85f).coerceAtLeast(1.dp.toPx())
                    drawLine(if (x in left..right) accent else muted, Offset(x, (size.height - height) / 2),
                        Offset(x, (size.height + height) / 2), (step * .7f).coerceAtLeast(1f))
                }
                for (x in listOf(left, right)) drawLine(accent, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                val x = playhead.toFloat() / duration * size.width
                drawLine(playheadColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(playheadColor, 5.dp.toPx(), Offset(x, 6.dp.toPx()))
            }
        }
        RangeSlider(value = start.toFloat()..end.toFloat(), onValueChange = { value ->
            if (value.start.roundToInt() != start) change(value.start.roundToInt().coerceAtMost(end - 3000), end)
            else change(start, value.endInclusive.roundToInt().coerceAtLeast(start + 3000))
        }, valueRange = 0f..duration.toFloat(), enabled = enabled && duration >= 3000,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trim-range"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trim_start, trimTime(start)), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrimStep("−0.1s", "Start 0.1 seconds earlier", enabled && start > 0, Modifier.weight(1f)) { change((start - 100).coerceAtLeast(0), end) }
                    TrimStep("+0.1s", "Start 0.1 seconds later", enabled && selected > 3000, Modifier.weight(1f)) { change((start + 100).coerceAtMost(end - 3000), end) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trim_end, trimTime(end)), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrimStep("−0.1s", "End 0.1 seconds earlier", enabled && selected > 3000, Modifier.weight(1f)) { change(start, (end - 100).coerceAtLeast(start + 3000)) }
                    TrimStep("+0.1s", "End 0.1 seconds later", enabled && end < duration, Modifier.weight(1f)) { change(start, (end + 100).coerceAtMost(duration)) }
                }
            }
        }
        Text(stringResource(R.string.trim_selected, trimTime(selected)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.trim_position, trimTime(playhead)), style = MaterialTheme.typography.labelLarge)
        if (!valid) Text(stringResource(R.string.trim_limits), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = preview, enabled = enabled && valid, modifier = Modifier.fillMaxWidth().testTag("trim-preview")) {
            Text(stringResource(if (state.playing) R.string.reader_pause else R.string.trim_preview))
        }
        if (state.key != null) PreviewControls(player)
    }
}

@Composable
private fun TrimStep(label: String, description: String, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    FilledTonalButton(onClick = action, enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, LocalGlass.current.border), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
