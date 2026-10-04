package org.pockettts.android.engine

import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlin.math.sqrt

internal class VoiceRecordingDialog(
    private val activity: Activity, private val packId: String,
    private val saved: () -> Unit, private val dismissed: () -> Unit,
    private val requestPermission: () -> Unit, private val imported: android.net.Uri? = null,
    private val extracted: File? = null, private val chooseAnother: (() -> Unit)? = null,
    private val editingVoice: PackVoice? = null
) {
    enum class Phase { PERMISSION_REQUIRED, PERMISSION_DENIED, READY, RECORDING, PROCESSING, TRIMMING, DENOISING, STOPPED, SAVING, FAILED }
    private val main = Handler(Looper.getMainLooper())
    private val recording = AtomicBoolean(false)
    private val scanCancelled = AtomicBoolean(false)
    private var working by mutableStateOf(false)
    @Volatile private var closed = false
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "pockettts-recording") }
    private var denoiser: DeepFilterNet3Denoiser? = null // Accessed only by worker.
    internal var phase by mutableStateOf(if (hasPermission()) Phase.READY else Phase.PERMISSION_REQUIRED)
        private set
    internal var elapsedMs by mutableIntStateOf(0)
        private set
    internal var levels by mutableStateOf(List(48) { 0f })
        private set
    private var name by mutableStateOf(editingVoice?.displayName ?: "")
    private var nameError by mutableStateOf(false)
    private var previewError by mutableStateOf(false)
    private var saveError by mutableStateOf(false)
    private val file = File(activity.cacheDir, "voice-${UUID.randomUUID()}.wav")
    private val enhanced = File(activity.cacheDir, "voice-enhanced-${UUID.randomUUID()}.wav")
    private val importedOriginal = File(activity.cacheDir, "voice-import-${UUID.randomUUID()}.wav")
    private val externalSample get() = imported != null || extracted != null || editingVoice != null
    private val original get() = extracted ?: if (imported != null || editingVoice != null) importedOriginal else file
    private val trimmed = File(activity.cacheDir, "voice-trim-${UUID.randomUUID()}.wav")
    private var trimInfo by mutableStateOf<WavSamples.Info?>(null)
    internal var trimStart by mutableIntStateOf(0)
        private set
    internal var trimEnd by mutableIntStateOf(0)
        private set
    internal var trimPosition by mutableIntStateOf(0)
        private set
    private var cachedRange: Pair<Int, Int>? = null
    private var trimApplied = false
    internal var volume by mutableFloatStateOf(1f)
        private set
    private var appliedVolume by mutableFloatStateOf(1f)
    private var cachedVolume = 1f
    private val beforeSample get() = if (trimApplied || volume != 1f) trimmed else original
    private var importError by mutableStateOf<String?>(null)
    internal var enhancedReady by mutableStateOf(false)
        private set
    private var useEnhanced by mutableStateOf(true)
    private var denoiseError by mutableStateOf(false)
    private var beforeLevels by mutableStateOf<List<Float>>(emptyList())
    private var afterLevels by mutableStateOf(List(48) { 0f })
    private val preview = SamplePreview(activity)
    init { if (extracted != null) inspectRecording() else if (imported != null || editingVoice != null) importWav() }
    private fun importWav() {
        working = true; phase = Phase.PROCESSING
        worker.execute {
            val result = runCatching {
                val source = if (editingVoice != null) {
                    val pack = requireNotNull(ModelPackRepository.find(activity, packId))
                    File(pack.voicesDir, editingVoice.fileName).inputStream()
                } else requireNotNull(activity.contentResolver.openInputStream(imported!!))
                source.use { input ->
                    importedOriginal.outputStream().use { output ->
                        val buffer = ByteArray(65536); var size = 0L
                        while (true) {
                            if (closed) error("Import cancelled")
                            val count = input.read(buffer); if (count < 0) break
                            size += count; require(size <= PcmWav.MAX_VOICE_BYTES) { PcmWav.SIZE_ERROR }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                WavSamples.inspect(importedOriginal, scanCancelled)
            }
            main.post {
                working = false
                if (closed) return@post
                if (result.isSuccess) {
                    showTrim(result.getOrThrow())
                } else {
                    importError = result.exceptionOrNull()?.message
                    denoiseError = true; useEnhanced = false
                    phase = Phase.FAILED
                }
            }
        }
    }
    private fun hasPermission() = activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun onPermissionResult(granted: Boolean) {
        if (!closed) phase = if (granted) Phase.READY else Phase.PERMISSION_DENIED
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content() {
        val permission = phase == Phase.PERMISSION_REQUIRED || phase == Phase.PERMISSION_DENIED
        val status = stringResource(when (phase) {
            Phase.PERMISSION_REQUIRED -> R.string.rec_permission_required
            Phase.PERMISSION_DENIED -> R.string.voice_permission_denied
            Phase.READY -> R.string.rec_ready
            Phase.RECORDING -> R.string.rec_recording
            Phase.PROCESSING -> R.string.rec_processing
            Phase.TRIMMING -> R.string.trim_title
            Phase.DENOISING -> R.string.df_processing
            Phase.STOPPED -> R.string.voice_record_ready
            Phase.SAVING -> R.string.rec_saving
            Phase.FAILED -> if (externalSample) R.string.error_invalid_wav else R.string.voice_record_failed
        })
        GlassSheet(if (editingVoice != null) "Edit audio · ${editingVoice.displayName}" else if (extracted != null) "Review extracted audio" else stringResource(if (externalSample) R.string.import_review_title else R.string.voice_record_title), ::close, footer = {
            if (phase == Phase.TRIMMING) {
                Button(onClick = { applyTrim(false) }, enabled = !working && trimEnd - trimStart in 3000..30000,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("trim-apply")) {
                    Text(stringResource(R.string.trim_use))
                }
            }
            if (phase == Phase.STOPPED || phase == Phase.SAVING) {
                if (nameError) Text(stringResource(R.string.voice_name_required), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = ::save, enabled = phase == Phase.STOPPED,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("record-save")) {
                    Text(if (editingVoice != null && phase != Phase.SAVING) "Save changes" else stringResource(if (phase == Phase.SAVING) R.string.rec_saving else R.string.voice_save))
                }
            }
        }) {
            item {
                Text(if (editingVoice != null) "Trim or enhance the current reference sample. Your original source stays recoverable through Restore Original in voice actions."
                    else stringResource(if (externalSample) R.string.import_review_help else R.string.rec_guidance), color = MaterialTheme.colorScheme.onSurfaceVariant)
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.rec_unsaved), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                if (!externalSample || elapsedMs > 0) Text(audioTime(elapsedMs) + if (!externalSample) " / 00:30" else "", style = MaterialTheme.typography.headlineLarge,
                    color = if (phase == Phase.RECORDING) LocalGlass.current.success else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("record-duration"))
                if (!externalSample && phase in listOf(Phase.READY, Phase.RECORDING, Phase.PERMISSION_REQUIRED, Phase.PERMISSION_DENIED)) Waveform(levels, phase == Phase.RECORDING)
                Text(status, color = when (phase) {
                    Phase.RECORDING, Phase.STOPPED -> LocalGlass.current.success
                    Phase.FAILED, Phase.PERMISSION_DENIED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("record-status"))
                if (phase == Phase.PROCESSING || phase == Phase.DENOISING || phase == Phase.SAVING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            if (phase == Phase.TRIMMING) item {
                trimInfo?.let { info ->
                    if (editingVoice != null) {
                        Text("Volume · ${(volume * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        Text("Adjust the saved audio, not just preview loudness. Gain is limited when needed to prevent clipping.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = volume, onValueChange = ::changeVolume, valueRange = .5f..2f, steps = 29,
                            enabled = !working, modifier = Modifier.fillMaxWidth().testTag("edit-volume")
                                .semantics { contentDescription = "Voice audio volume" })
                        OutlinedButton(onClick = { changeVolume(1f) }, enabled = !working && volume != 1f) { Text("Reset to 100%") }
                    }
                    TrimControls(info, trimStart, trimEnd, ::changeTrim, preview, trimPosition, ::seekTrim,
                        enabled = !working, preview = {
                            if (preview.state.key != null) preview.toggle() else prepareSelection(false)
                        })
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (previewError) Text(stringResource(R.string.reader_error_output), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { applyTrim(true) }, enabled = !working,
                        modifier = Modifier.fillMaxWidth().testTag("trim-skip")) { Text(stringResource(R.string.trim_skip)) }
                }
            }
            if (phase == Phase.FAILED && externalSample && chooseAnother != null) item {
                OutlinedButton(onClick = { close(); chooseAnother.invoke() }, modifier = Modifier.fillMaxWidth().testTag("import-replace")) {
                    Text(stringResource(R.string.choose_another_wav))
                }
            }
            if (!externalSample) item {
                if (permission) {
                    Button(onClick = requestPermission, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.rec_allow_microphone))
                    }
                    TextButton(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rec_back_methods)) }
                    if (phase == Phase.PERMISSION_DENIED) TextButton(onClick = {
                        activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${activity.packageName}")))
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rec_permission_settings)) }
                } else {
                    Button(onClick = { if (phase == Phase.RECORDING) stop() else start() },
                        enabled = phase == Phase.RECORDING || !working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("record-control")) {
                        Text(stringResource(if (phase == Phase.RECORDING) R.string.reader_stop else R.string.voice_record))
                    }
                }
            }
            if (phase == Phase.STOPPED || phase == Phase.SAVING) {
                item {
                    SectionTitle(stringResource(R.string.rec_review))
                    if (editingVoice != null && volume != 1f) Text("Volume applied · ${(appliedVolume * 100).toInt()}%" +
                        if (appliedVolume + .001f < volume) " (limited to prevent clipping)" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (trimInfo != null) OutlinedButton(onClick = ::adjustTrim, enabled = !working,
                        modifier = Modifier.fillMaxWidth().testTag("adjust-trim")) { Text(stringResource(R.string.trim_adjust)) }
                    Text(stringResource(if (externalSample) R.string.import_review_help else R.string.rec_review_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (denoiseError) {
                        Text(stringResource(R.string.df_failed), color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = ::denoise, enabled = !working, modifier = Modifier.testTag("denoise-retry")) { Text(stringResource(R.string.df_retry)) }
                    }
                }
                item { Comparison(false) }
                if (enhancedReady) item { Comparison(true) }
                item {
                    if (preview.state.key != null) {
                        Text(preview.state.title, style = MaterialTheme.typography.titleSmall)
                        PreviewControls(preview)
                    }
                    if (previewError) Text(stringResource(R.string.reader_error_output), color = MaterialTheme.colorScheme.error)
                    if (saveError) Text(stringResource(R.string.voice_save_failed), color = MaterialTheme.colorScheme.error)
                }
                if (editingVoice == null) item {
                    OutlinedTextField(name, { name = it; nameError = false }, label = { Text(stringResource(R.string.voice_name)) },
                        singleLine = true, isError = nameError, enabled = phase != Phase.SAVING,
                        modifier = Modifier.fillMaxWidth().testTag("record-name"),
                        supportingText = { if (nameError) Text(stringResource(R.string.voice_name_required)) })
                }
            }
            item { TextButton(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) } }
        }
    }
    internal fun adjustTrim() {
        if (closed || working || phase != Phase.STOPPED || trimInfo == null) return
        preview.close(); previewError = false; nameError = false
        phase = Phase.TRIMMING
    }
    fun close() {
        if (closed) return
        closed = true; recording.set(false); scanCancelled.set(true); preview.close()
        // Queue release after in-flight inference. Never free a session still in Run().
        worker.execute { denoiser?.close(); denoiser = null; file.delete(); enhanced.delete(); importedOriginal.delete(); trimmed.delete(); extracted?.delete() }
        worker.shutdown()
        dismissed()
    }
    private fun stop() {
        recording.set(false)
        phase = Phase.PROCESSING
    }
    private fun start() {
        if (working || closed) return
        if (!hasPermission()) { phase = Phase.PERMISSION_REQUIRED; return }
        preview.close(); previewError = false; saveError = false; working = true; recording.set(true)
        enhancedReady = false; denoiseError = false; enhanced.delete()
        trimInfo = null; cachedRange = null; trimApplied = false; trimmed.delete()
        elapsedMs = 0; levels = List(48) { 0f }; phase = Phase.RECORDING
        worker.execute {
            var recorder: AudioRecord? = null
            val result = runCatching {
                val size = AudioRecord.getMinBufferSize(PcmWav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(size > 0)
                if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Microphone permission was revoked")
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, PcmWav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                val audio = requireNotNull(recorder)
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                PcmWav.Writer(file).use { output ->
                    // 50 ms chunks: bounded 20 Hz UI updates, independent of display refresh.
                    val buffer = ByteArray(PcmWav.RATE / 20 * 2)
                    while (recording.get() && output.bytes < PcmWav.RATE * 2 * 30) {
                        val count = audio.read(buffer, 0, minOf(buffer.size, (PcmWav.RATE * 2 * 30 - output.bytes).toInt()))
                        check(count > 0)
                        output.write(buffer, count)
                        val peak = pcmPeak(buffer, count)
                        val duration = (output.bytes * 1000 / (PcmWav.RATE * 2)).toInt()
                        main.post {
                            if (!closed && phase == Phase.RECORDING) {
                                levels = levels.drop(1) + peak
                                elapsedMs = duration
                            }
                        }
                    }
                    check(output.bytes >= PcmWav.RATE * 2 * 3) { "Record at least three seconds" }
                }
            }
            runCatching { recorder?.stop() }; recorder?.release()
            result.exceptionOrNull()?.let { Log.e("PocketTTS", "VOICE_RECORD_FAILED", it) }
            main.post {
                working = false; recording.set(false)
                if (closed) return@post
                if (result.isSuccess) {
                    elapsedMs = ((file.length() - 44) * 1000 / (PcmWav.RATE * 2)).toInt()
                    inspectRecording()
                } else phase = if (!hasPermission()) Phase.PERMISSION_DENIED else Phase.FAILED
            }
        }
    }
    private fun inspectRecording() {
        working = true; phase = Phase.PROCESSING
        worker.execute {
            val result = runCatching { WavSamples.inspect(original, scanCancelled) }
            main.post {
                working = false
                if (closed) return@post
                result.onSuccess(::showTrim).onFailure { importError = it.message; phase = Phase.FAILED }
            }
        }
    }
    private fun showTrim(info: WavSamples.Info) {
        trimInfo = info; elapsedMs = info.durationMs; trimStart = 0; trimEnd = info.durationMs
        trimPosition = 0
        cachedRange = null; trimApplied = false; phase = Phase.TRIMMING
    }
    internal fun changeTrim(start: Int, end: Int) {
        if (working || phase != Phase.TRIMMING) return
        val duration = trimInfo?.durationMs ?: return
        if (duration < 3000) return
        if (preview.state.key != null) trimPosition = trimStart + preview.state.positionMs
        preview.close(); previewError = false
        trimStart = start.coerceIn(0, duration - 3000)
        trimEnd = end.coerceIn(trimStart + 3000, duration)
        trimPosition = trimPosition.coerceIn(trimStart, trimEnd)
    }
    internal fun changeVolume(value: Float) {
        if (working || closed || phase != Phase.TRIMMING || editingVoice == null || !value.isFinite()) return
        preview.close(); volume = value.coerceIn(.5f, 2f); cachedRange = null
        enhancedReady = false; previewError = false
    }
    internal fun seekTrim(position: Int) {
        if (closed || phase != Phase.TRIMMING) return
        trimPosition = position.coerceIn(trimStart, trimEnd)
        if (working) return
        if (preview.state.key != null) preview.seekTo(trimPosition - trimStart)
        else prepareSelection(false, autoPlay = false)
    }
    internal fun applyTrim(skip: Boolean) {
        if (working || closed || phase != Phase.TRIMMING) return
        preview.close()
        if (skip) {
            trimStart = 0; trimEnd = trimInfo?.durationMs ?: return
            if (trimEnd !in 3000..30000) {
                if (volume != 1f) { importError = "Volume editing requires a 3–30-second selection."; return }
                trimApplied = false; enhancedReady = false; useEnhanced = false; denoiseError = true
                cachedRange = null; beforeLevels = emptyList()
                importError = activity.getString(R.string.trim_limits)
                phase = Phase.STOPPED
                return
            }
        }
        trimApplied = !skip
        prepareSelection(true)
    }
    private fun prepareSelection(apply: Boolean, autoPlay: Boolean = true) {
        if (working || closed || trimEnd - trimStart !in 3000..30000) return
        preview.close(); working = true; previewError = false
        val range = trimStart to trimEnd
        worker.execute {
            val result = runCatching {
                if (cachedRange != range || cachedVolume != volume || !trimmed.isFile)
                    WavSamples.trim(original, trimmed, range.first, range.second, volume) else appliedVolume
            }
            main.post {
                working = false
                if (closed) return@post
                if (result.isFailure) { previewError = true; return@post }
                cachedRange = range
                cachedVolume = volume; appliedVolume = result.getOrThrow()
                if (apply) denoise()
                else preview.play(trimmed, activity.getString(R.string.trim_preview),
                    positionMs = (trimPosition - trimStart).let { if (autoPlay && it >= trimEnd - trimStart) 0 else it },
                    autoPlay = autoPlay, retain = true) { previewError = true }
            }
        }
    }
    private fun denoise() {
        if (closed || working) return
        preview.close(); working = true; phase = Phase.DENOISING; denoiseError = false
        worker.execute {
            val result = runCatching {
                if (cachedRange != (trimStart to trimEnd) || cachedVolume != volume || !trimmed.isFile) {
                    val actual = WavSamples.trim(original, trimmed, trimStart, trimEnd, volume)
                    main.post { if (!closed) appliedVolume = actual }
                }
                val before = DeepFilterNet3Denoiser.waveform(DeepFilterNet3Denoiser.readRecording(trimmed))
                main.post { if (!closed) beforeLevels = before }
                if (closed) return@runCatching null
                val engine = denoiser ?: DeepFilterNet3Denoiser(activity.applicationContext).also { denoiser = it }
                if (closed) return@runCatching null
                engine.processWav(trimmed, enhanced)
            }
            result.exceptionOrNull()?.let {
                Log.e("PocketTTS", "DENOISE_FAILED", it)
                denoiser?.close(); denoiser = null; enhanced.delete()
            }
            main.post {
                working = false
                if (closed) return@post
                val waveforms = result.getOrNull()
                enhancedReady = waveforms != null
                useEnhanced = enhancedReady
                denoiseError = !enhancedReady
                waveforms?.let { beforeLevels = it.first; afterLevels = it.second }
                if (enhancedReady) importError = null
                phase = Phase.STOPPED
            }
        }
    }
    @Composable
    private fun Comparison(after: Boolean) {
        val title = stringResource(if (after) R.string.df_after else if (trimApplied) R.string.trim_before else R.string.df_before)
        GlassCard(Modifier.fillMaxWidth().testTag(if (after) "denoise-after" else "denoise-before"), selected = useEnhanced == after) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = if (after) LocalGlass.current.success else MaterialTheme.colorScheme.onSurface)
            if (after || beforeLevels.isNotEmpty()) Waveform(if (after) afterLevels else beforeLevels, false, title)
            OutlinedButton(onClick = {
                previewError = false
                preview.play(if (after) enhanced else beforeSample, title) { previewError = true }
            }, enabled = !working, modifier = Modifier.fillMaxWidth().testTag(if (after) "preview-after" else "preview-before")) {
                Text(stringResource(R.string.voice_preview))
            }
            Row {
                RadioButton(selected = useEnhanced == after, onClick = { useEnhanced = after }, enabled = !working)
                TextButton(onClick = { useEnhanced = after }, enabled = !working) {
                    Text(stringResource(if (after) R.string.df_use_enhanced else if (trimApplied) R.string.trim_use_before else R.string.df_use_original))
                }
            }
        }
    }
    private fun save() {
        if (phase != Phase.STOPPED || working || closed) return
        if (name.isBlank()) { nameError = true; return }
        preview.close(); saveError = false; working = true; phase = Phase.SAVING
        val label = name
        val selectedFile = if (useEnhanced && enhancedReady) enhanced else beforeSample
        val hasEnhanced = enhancedReady
        worker.execute {
            val result = runCatching {
                check(!closed) { "Saving cancelled" }
                if (editingVoice != null) ModelPackRepository.updateVoiceAudio(activity.applicationContext, packId, editingVoice.id,
                    selectedFile, beforeSample, enhanced.takeIf { hasEnhanced }, cancelled = { closed })
                else ModelPackRepository.importRecording(activity.applicationContext, packId, label,
                    selectedFile, original = original, enhanced = enhanced.takeIf { hasEnhanced }, trimmed = trimmed.takeIf { trimApplied })
            }
            result.exceptionOrNull()?.let { Log.e("PocketTTS", "VOICE_SAVE_FAILED", it) }
            main.post {
                working = false
                if (result.isSuccess) {
                    if (!activity.isDestroyed) saved()
                    if (!closed) close()
                } else if (!closed) { phase = Phase.STOPPED; saveError = true }
            }
        }
    }
}

@Composable
private fun Waveform(levels: List<Float>, recording: Boolean, label: String? = null) {
    val color = if (recording) LocalGlass.current.success else MaterialTheme.colorScheme.primary
    val description = label ?: stringResource(R.string.rec_waveform)
    Canvas(Modifier.fillMaxWidth().height(88.dp).padding(vertical = 12.dp)
        .semantics { contentDescription = description }.testTag("record-waveform")) {
        val step = size.width / levels.size
        levels.forEachIndexed { index, amplitude ->
            val height = (sqrt(amplitude.coerceIn(0f, 1f)) * size.height).coerceAtLeast(2.dp.toPx())
            val x = (index + .5f) * step
            drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                strokeWidth = (step * .45f).coerceAtMost(4.dp.toPx()), cap = StrokeCap.Round)
        }
    }
}
