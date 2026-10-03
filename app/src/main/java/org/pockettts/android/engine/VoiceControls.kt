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
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(if (state.loading) R.string.reader_loading else if (state.playing) R.string.reader_playing_notification else R.string.reader_paused),
            color = if (state.playing) LocalGlass.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (peaks.isNotEmpty()) PlaybackWaveform(peaks, 1L, peaks.size.toLong(), state.positionMs.toLong(), state.durationMs.toLong(),
            { player.seekTo(it.toInt()) }, "preview-waveform", "Sample waveform")
        Slider(value = seeking ?: state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat()),
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = !state.loading && state.durationMs > 0,
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { player.seekTo(it.toInt()) }; seeking = null },
            modifier = Modifier.semantics { contentDescription = "Preview position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = player::toggle, enabled = !state.loading, modifier = Modifier.testTag("preview-toggle")) {
                Text(stringResource(if (state.playing) R.string.reader_pause else R.string.reader_resume))
            }
            TextButton(onClick = player::close, modifier = Modifier.testTag("preview-stop")) { Text(stringResource(R.string.reader_stop)) }
        }
        Text("${audioTime(state.positionMs)} / ${audioTime(state.durationMs)}", style = MaterialTheme.typography.labelMedium)
        SpeedControl(player.speed, player::changeSpeed)
    }
}

@Composable
internal fun SpeedControl(speed: Float, change: (Float) -> Unit) {
    val label = stringResource(R.string.reader_speed)
    Text("$label · ${PlaybackSpeed.label(speed)}", style = MaterialTheme.typography.titleMedium)
    Slider(value = speed, onValueChange = change, valueRange = .5f..2f, steps = 29,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag("playback-speed"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0.50×", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { change(1f) }) { Text("1.00×") }
        Text("2.00×", style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddVoiceSheet(app: MainActivity) {
    val pack = app.packs.firstOrNull { it.id == BundledPocketTts.ID }
    GlassSheet(stringResource(R.string.voice_add), { app.sheet = null }) {
        item { Text(stringResource(R.string.add_voice_help), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pack == null) item {
            Text(stringResource(R.string.status_no_model))
            Button(onClick = { app.sheet = null; app.switchTab(2) }) { Text(stringResource(R.string.tab_settings)) }
        } else {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.voice_record))
                    Text(stringResource(R.string.rec_guidance), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { app.addVoice(pack, true) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(stringResource(R.string.voice_record))
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.import_reference_voice))
                    Text(stringResource(R.string.add_import_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { app.addVoice(pack, false) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(stringResource(R.string.import_reference_voice))
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("Extract from Video")
                    Text("Select a video, preview it, then extract and trim one speaker’s audio for a custom voice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { app.pickVideo(pack) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("add-video")) {
                        Text("Extract from Video")
                    }
                }
            }
        }
    }
}
