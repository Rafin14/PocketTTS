@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.pockettts.android.engine

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pockettts.android.engine.StudioButton as Button
import org.pockettts.android.engine.StudioTonalButton as FilledTonalButton
import org.pockettts.android.engine.StudioOutlinedButton as OutlinedButton
import org.pockettts.android.engine.StudioSlider as Slider
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment

internal fun audioTime(ms: Int) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)

@Composable
internal fun PreviewControls(player: SamplePreview) {
    val state = player.state
    var peaks by remember(state.key) { mutableStateOf<List<Float>>(emptyList()) }
    val cancellation = remember(state.key) { AtomicBoolean(false) }
    DisposableEffect(state.key) {
        onDispose { cancellation.set(true) }
    }
    LaunchedEffect(state.key) {
        val file = state.key?.let(::File)
        if (file?.isFile == true) {
            try {
                peaks = withContext(Dispatchers.IO) { runCatching { WavSamples.inspect(file, cancellation).peaks }.getOrDefault(emptyList()) }
            } finally { cancellation.set(true) }
        }
    }
    var seeking by remember(state.key) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if (state.loading) R.string.preview_loading else if (state.playing) R.string.preview_playing else R.string.reader_paused),
            color = if (state.playing) LocalGlass.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (peaks.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp),
            border = BorderStroke(.75.dp, LocalGlass.current.border)) {
            Box(Modifier.padding(12.dp)) { PlaybackWaveform(peaks, 1L, peaks.size.toLong(), state.positionMs.toLong(), state.durationMs.toLong(),
                { player.seekWithFeedback(it.toInt()) }, "preview-waveform", "Sample waveform", height = 72.dp) }
        }
        Slider(value = seeking ?: state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat()),
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = !state.loading && state.durationMs > 0,
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { player.seekWithFeedback(it.toInt()) }; seeking = null },
            modifier = Modifier.semantics { contentDescription = "Preview position" })
        SampleTransport(player)
        Text("${audioTime(state.positionMs)} / ${audioTime(state.durationMs)}", style = MaterialTheme.typography.labelMedium)
        SpeedControl(player.speed, player::changeSpeed)
    }
}

@Composable
internal fun SpeedControl(speed: Float, change: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val label = stringResource(R.string.reader_speed)
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(PlaybackSpeed.label(speed), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    StudioSpeedPresets(speed, change)
    Slider(value = speed, onValueChange = change, valueRange = .5f..2f, steps = 29,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag("playback-speed"))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(StudioTokens.ActionGap)) {
        Text("0.50×", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { change(1f) }, modifier = Modifier.testTag("speed-reset")) { Text(stringResource(R.string.ui_speed_reset)) }
        Text("2.00×", style = MaterialTheme.typography.labelMedium)
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddVoiceSheet(app: MainActivity) {
    val pack = app.packs.firstOrNull { it.id == BundledPocketTts.ID }
    GlassSheet(stringResource(R.string.voice_add), { app.sheet = null }) {
        item { Text(stringResource(R.string.ui_add_summary), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pack == null) item {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.ControlGap)) {
            Text(stringResource(R.string.status_no_model))
            Button(onClick = { app.sheet = null; app.switchTab(2) }) { Text(stringResource(R.string.tab_settings)) }
            }
        } else {
            item {
                GlassCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceIdentity("Record", compact = true)
                        Text(stringResource(R.string.studio_record_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.ui_record_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { app.addVoice(pack, true) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.voice_record))
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceIdentity("WAV", compact = true)
                        Text(stringResource(R.string.studio_import_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.add_import_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { app.addVoice(pack, false) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.import_reference_voice))
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceIdentity("Video", compact = true)
                        Text(stringResource(R.string.studio_video_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.ui_video_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { app.pickVideo(pack) }, modifier = Modifier.align(Alignment.End).testTag("add-video")) {
                        Text(stringResource(R.string.ui_video_title))
                    }
                }
            }
        }
    }
}
