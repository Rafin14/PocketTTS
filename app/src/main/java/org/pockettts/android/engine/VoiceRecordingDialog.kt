@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.Alignment
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
import org.pockettts.android.engine.StudioButton as Button
import org.pockettts.android.engine.StudioTonalButton as FilledTonalButton
import org.pockettts.android.engine.StudioOutlinedButton as OutlinedButton
import org.pockettts.android.engine.StudioSlider as Slider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight

internal class VoiceRecordingDialog(
    private val activity: Activity, private val packId: String,
    private val saved: (ModelPack) -> Unit, private val dismissed: () -> Unit,
    private val requestPermission: () -> Unit, private val imported: android.net.Uri? = null,
    private val extracted: File? = null, private val chooseAnother: (() -> Unit)? = null,
    private val editingVoice: PackVoice? = null
) {
    enum class Phase { PERMISSION_REQUIRED, PERMISSION_DENIED, READY, RECORDING, PROCESSING, TRIMMING, DENOISING, REVIEW_PROCESSING, STOPPED, SAVING, FAILED }
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
    private val rawTrim = File(activity.cacheDir, "voice-raw-trim-${UUID.randomUUID()}.wav")
    private val rawEnhanced = File(activity.cacheDir, "voice-raw-enhanced-${UUID.randomUUID()}.wav")
    private var enhancedVolume = 1f
    private var reviewDirty by mutableStateOf(false)
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
    private var normalizationTarget: Double? = editingVoice?.edit?.targetLufs
    private var gainNotice by mutableStateOf<String?>(null)
    private var editChanged = false
    private val validReview get() = cachedRange == (trimStart to trimEnd) && trimEnd - trimStart in 3000..30000 && rawTrim.isFile
    private val beforeSample get() = if (validReview) trimmed else original
    private var importError by mutableStateOf<String?>(null)
    internal var enhancedReady by mutableStateOf(false)
        private set
    private var useEnhanced by mutableStateOf(true)
    private var denoiseError by mutableStateOf(false)
    private var beforeLevels by mutableStateOf<List<Float>>(emptyList())
    private var afterLevels by mutableStateOf(List(48) { 0f })
    private val preview = SamplePreview(activity)
    private val feedback get() = (activity as? MainActivity)?.haptics
    init { if (extracted != null) inspectRecording() else if (imported != null || editingVoice != null) importWav() }
    private fun importWav() {
        working = true; phase = Phase.PROCESSING
        worker.execute {
            val result = runCatching {
                val source = if (editingVoice != null) {
                    val pack = requireNotNull(ModelPackRepository.find(activity, packId))
                    ModelPackRepository.editingSource(pack, editingVoice).inputStream()
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
        val reviewing = phase in listOf(Phase.STOPPED, Phase.REVIEW_PROCESSING, Phase.SAVING)
        val permission = phase == Phase.PERMISSION_REQUIRED || phase == Phase.PERMISSION_DENIED
        val status = stringResource(when (phase) {
            Phase.PERMISSION_REQUIRED -> R.string.rec_permission_required
            Phase.PERMISSION_DENIED -> R.string.voice_permission_denied
            Phase.READY -> R.string.rec_ready
            Phase.RECORDING -> R.string.rec_recording
            Phase.PROCESSING -> if (rawTrim.isFile) R.string.review_processing else R.string.rec_processing
            Phase.TRIMMING -> R.string.trim_title
            Phase.DENOISING -> R.string.df_processing
            Phase.REVIEW_PROCESSING -> R.string.review_processing
            Phase.STOPPED -> if (reviewDirty) R.string.review_pending else R.string.voice_record_ready
            Phase.SAVING -> R.string.rec_saving
            Phase.FAILED -> if (externalSample) R.string.error_invalid_wav else R.string.voice_record_failed
        })
        GlassSheet(if (editingVoice != null) stringResource(R.string.audio_edit_title, editingVoice.displayName) else if (extracted != null) stringResource(R.string.audio_video_review) else stringResource(if (externalSample) R.string.import_review_title else R.string.voice_record_title), ::close, footer = {
            if (phase == Phase.TRIMMING) {
                Button(onClick = { applyTrim(false) }, enabled = !working && trimEnd - trimStart in 3000..30000,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("trim-apply")) {
                    Text(stringResource(R.string.trim_use))
                }
            }
            if (reviewing) {
                if (nameError) Text(stringResource(R.string.voice_name_required), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = ::save, enabled = phase == Phase.STOPPED && !working && !reviewDirty,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("record-save")) {
                    Text(stringResource(if (editingVoice != null && phase != Phase.SAVING) R.string.audio_save_changes else if (phase == Phase.SAVING) R.string.rec_saving else R.string.voice_save))
                }
            }
        }, prioritizeContentScroll = reviewing) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase in listOf(Phase.READY, Phase.PERMISSION_REQUIRED, Phase.PERMISSION_DENIED))
                    Text(stringResource(R.string.rec_guidance), color = MaterialTheme.colorScheme.onSurfaceVariant)
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.rec_unsaved), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.ControlGap)) {
                val displayDuration = if (reviewing && validReview) trimEnd - trimStart else elapsedMs
                if (!externalSample || elapsedMs > 0) Text(audioTime(displayDuration) + if (!externalSample && phase == Phase.RECORDING) " / 00:30" else "", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium,
                    color = if (phase == Phase.RECORDING) LocalGlass.current.success else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("record-duration"))
                if (!externalSample && phase in listOf(Phase.READY, Phase.RECORDING, Phase.PERMISSION_REQUIRED, Phase.PERMISSION_DENIED)) Waveform(levels, phase == Phase.RECORDING)
                Text(status, color = when (phase) {
                    Phase.RECORDING -> LocalGlass.current.success
                    Phase.STOPPED -> if (reviewDirty) MaterialTheme.colorScheme.onSurfaceVariant else LocalGlass.current.success
                    Phase.FAILED, Phase.PERMISSION_DENIED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("record-status"))
                if (reviewing) Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else HorizontalDivider(thickness = .5.dp, color = LocalGlass.current.border)
                } else if (phase == Phase.PROCESSING || phase == Phase.DENOISING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
            if (phase == Phase.TRIMMING) item {
                trimInfo?.let { info ->
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.ControlGap)) {
                    TrimControls(info, trimStart, trimEnd, ::changeTrim, preview, trimPosition, ::seekTrim,
                        enabled = !working, preview = {
                            if (preview.state.key != null) preview.toggle() else prepareSelection(false)
                        })
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (previewError) Text(stringResource(R.string.reader_error_output), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { applyTrim(true) }, enabled = !working,
                        modifier = Modifier.testTag("trim-skip")) { Text(stringResource(R.string.trim_skip)) }
                    }
                }
            }
            if (phase == Phase.FAILED && externalSample && chooseAnother != null) item {
                OutlinedButton(onClick = { close(); chooseAnother.invoke() }, modifier = Modifier.fillMaxWidth().testTag("import-replace")) {
                    Text(stringResource(R.string.choose_another_wav))
                }
            }
            if (!externalSample) item {
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.ActionGap)) {
                if (permission) {
                    Button(onClick = requestPermission, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rec_allow_microphone))
                    }
                    if (phase == Phase.PERMISSION_DENIED) OutlinedButton(onClick = {
                        activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${activity.packageName}")))
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rec_permission_settings)) }
                } else {
                    Button(onClick = { if (phase == Phase.RECORDING) stop() else start() },
                        enabled = phase == Phase.RECORDING || !working,
                        modifier = Modifier.fillMaxWidth().testTag("record-control")) {
                        Text(stringResource(if (phase == Phase.RECORDING) R.string.reader_stop else R.string.voice_record))
                    }
                }
                }
            }
            if (reviewing) {
                item { ReviewGain() }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.ControlGap)) {
                    HorizontalDivider(thickness = .5.dp, color = LocalGlass.current.border)
                    Text(stringResource(R.string.rec_review), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    val selectedGain = if (useEnhanced && enhancedReady) enhancedVolume else appliedVolume
                    Text(stringResource(R.string.audio_gain_applied, PcmGain.label(PcmGain.db(selectedGain))) +
                        if (selectedGain + .001f < volume) " · " + stringResource(R.string.audio_gain_limited) else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (trimInfo != null) OutlinedButton(onClick = ::adjustTrim, enabled = !working,
                        modifier = Modifier.testTag("adjust-trim")) { Text(stringResource(R.string.trim_adjust)) }
                    if (editingVoice != null) ContextHelp(stringResource(R.string.audio_edit_help))
                    if (denoiseError) {
                        Text(stringResource(R.string.df_failed), color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = ::denoise, enabled = !working, modifier = Modifier.testTag("denoise-retry")) { Text(stringResource(R.string.df_retry)) }
                    }
                    }
                }
                item { Comparison(false) }
                if (enhancedReady) item { Comparison(true) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.ControlGap)) {
                    if (previewError) Text(stringResource(R.string.reader_error_output), color = MaterialTheme.colorScheme.error)
                    if (saveError) Text(stringResource(R.string.voice_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (editingVoice == null) item {
                    OutlinedTextField(name, { name = it; nameError = false }, label = { Text(stringResource(R.string.voice_name)) },
                        singleLine = true, isError = nameError, enabled = !working,
                        modifier = Modifier.fillMaxWidth().testTag("record-name"),
                        supportingText = { if (nameError) Text(stringResource(R.string.voice_name_required)) })
                }
            }
            item { OutlinedButton(onClick = ::close) { Text(stringResource(android.R.string.cancel)) } }
        }
    }
    internal fun adjustTrim() {
        if (closed || working || phase != Phase.STOPPED || trimInfo == null) return
        preview.close(); previewError = false; nameError = false
        phase = Phase.TRIMMING; cachedRange = null; enhancedReady = false
    }
    fun close() {
        if (closed) return
        closed = true; recording.set(false); scanCancelled.set(true); preview.close()
        // Queue release after in-flight inference. Never free a session still in Run().
        worker.execute { denoiser?.close(); denoiser = null; file.delete(); enhanced.delete(); importedOriginal.delete(); trimmed.delete(); rawTrim.delete(); rawEnhanced.delete(); extracted?.delete() }
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
        enhancedReady = false; denoiseError = false; enhanced.delete(); rawTrim.delete(); rawEnhanced.delete(); reviewDirty = false
        trimInfo = null; cachedRange = null; trimApplied = false; trimmed.delete()
        volume = 1f; normalizationTarget = null; gainNotice = null; editChanged = false
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
                    feedback?.confirm()
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
        trimInfo = info; elapsedMs = info.durationMs
        trimStart = editingVoice?.edit?.startMs ?: 0
        trimEnd = editingVoice?.edit?.endMs ?: info.durationMs
        volume = editingVoice?.edit?.let { PcmGain.linear(it.gainDb) } ?: 1f
        editingVoice?.edit?.let { recipe ->
            appliedVolume = Math.pow(10.0, recipe.effectiveDb / 20.0).toFloat()
            gainNotice = activity.getString(R.string.audio_gain_applied, PcmGain.label(recipe.effectiveDb)) +
                (recipe.targetLufs?.let { " · " + activity.getString(R.string.audio_normalized_saved, it) } ?: "")
        }
        trimPosition = 0
        cachedRange = null; trimApplied = false; phase = Phase.TRIMMING
    }
    internal fun changeTrim(start: Int, end: Int) {
        if (working || phase != Phase.TRIMMING) return
        val duration = trimInfo?.durationMs ?: return
        if (duration < 3000) return
        if (start != trimStart || end != trimEnd) editChanged = true
        if (preview.state.key != null) trimPosition = trimStart + preview.state.positionMs
        preview.close(); previewError = false
        val oldStart = trimStart; val oldEnd = trimEnd
        trimStart = start.coerceIn(0, duration - 3000)
        trimEnd = end.coerceIn(trimStart + 3000, duration)
        trimPosition = trimPosition.coerceIn(trimStart, trimEnd)
        feedback?.value(oldStart.toFloat(), trimStart.toFloat(), 100f, 0f, (trimEnd - 3000).toFloat())
        feedback?.value(oldEnd.toFloat(), trimEnd.toFloat(), 100f, (trimStart + 3000).toFloat(), duration.toFloat())
    }
    internal fun changeVolume(value: Float) {
        if (working || closed || phase !in listOf(Phase.TRIMMING, Phase.STOPPED) || !value.isFinite()) return
        if (value != volume || normalizationTarget != null) editChanged = true
        normalizationTarget = null; gainNotice = null
        val previous = volume
        preview.close(); volume = value.coerceIn(.5f, 2f); previewError = false
        feedback?.value(PcmGain.db(previous), PcmGain.db(volume), 1f, PcmGain.MIN_DB, PcmGain.MAX_DB)
        if (phase == Phase.STOPPED) reviewDirty = true
    }
    private fun normalize() {
        if (working || closed || phase != Phase.STOPPED || !validReview) return
        preview.close(); working = true; phase = Phase.REVIEW_PROCESSING
        worker.execute {
            val result = runCatching {
                val pack = requireNotNull(ModelPackRepository.find(activity, BundledPocketTts.ID))
                val alba = pack.voices.first { it.id == "alba" }
                val reference = ModelPackRepository.originalSource(pack, alba)
                val duration = WavSamples.inspect(reference, scanCancelled).durationMs.coerceAtMost(30000)
                // Calibrated to the shipped reference, bounded for short-reference use.
                val target = Loudness.measure(WavSamples.decode(reference, 0, duration), cancelled = { closed }).lufs
                require(target.isFinite()) { "Reference loudness could not be measured" }
                val calibrated = target.coerceIn(-24.0, -16.0)
                val adjustment = Loudness.adjustment(DeepFilterNet3Denoiser.readRecording(if (useEnhanced && enhancedReady) rawEnhanced else rawTrim), calibrated) { closed }
                calibrated to adjustment
            }
            main.post {
                working = false
                if (closed) return@post
                phase = Phase.STOPPED
                result.onSuccess { (target, adjustment) ->
                    changeVolume(PcmGain.linear(adjustment.gainDb)); editChanged = true; normalizationTarget = target
                    gainNotice = activity.getString(R.string.audio_normalized, target, adjustment.measurement.lufs) +
                        if (adjustment.limited) " · " + activity.getString(R.string.audio_gain_limited) else ""
                    renderReview()
                }.onFailure { gainNotice = activity.getString(if (it is Loudness.SilentInput) R.string.audio_normalize_silent else R.string.audio_normalize_failed) }
            }
        }
    }
    internal fun seekTrim(position: Int) {
        if (closed || phase != Phase.TRIMMING) return
        val previous = trimPosition
        trimPosition = position.coerceIn(trimStart, trimEnd)
        if ((trimPosition == trimStart || trimPosition == trimEnd) && trimPosition != previous) feedback?.tick()
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
                if (volume != 1f || normalizationTarget != null) { importError = activity.getString(R.string.audio_gain_selection_required); return }
                trimApplied = false; enhancedReady = false; useEnhanced = false; denoiseError = true
                cachedRange = null; beforeLevels = emptyList(); reviewDirty = false
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
                if (cachedRange != range || !rawTrim.isFile)
                    WavSamples.trim(original, rawTrim, range.first, range.second, 1f) else 1f
            }
            main.post {
                working = false
                if (closed) return@post
                if (result.isFailure) { previewError = true; return@post }
                cachedRange = range
                appliedVolume = result.getOrThrow()
                if (apply) denoise()
                else preview.play(rawTrim, activity.getString(R.string.trim_preview),
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
                if (cachedRange != (trimStart to trimEnd) || !rawTrim.isFile) {
                    WavSamples.trim(original, rawTrim, trimStart, trimEnd, 1f)
                }
                val before = DeepFilterNet3Denoiser.waveform(DeepFilterNet3Denoiser.readRecording(rawTrim))
                main.post { if (!closed) beforeLevels = before }
                if (closed) return@runCatching null
                val engine = denoiser ?: DeepFilterNet3Denoiser(activity.applicationContext).also { denoiser = it }
                if (closed) return@runCatching null
                engine.processWav(rawTrim, rawEnhanced)
                DeepFilterNet3Denoiser.waveform(DeepFilterNet3Denoiser.readRecording(rawTrim)) to
                    DeepFilterNet3Denoiser.waveform(DeepFilterNet3Denoiser.readRecording(rawEnhanced))
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
                if (editingVoice?.edit != null && !editChanged) useEnhanced = editingVoice.edit.enhanced && enhancedReady
                denoiseError = !enhancedReady
                waveforms?.let { beforeLevels = it.first; afterLevels = it.second }
                if (enhancedReady) importError = null
                if (enhancedReady) feedback?.confirm()
                phase = Phase.STOPPED
                cachedRange = trimStart to trimEnd
                renderReview()
            }
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ReviewGain() {
        GlassCard(spacing = 12.dp, padding = 18.dp) {
            Text(stringResource(R.string.audio_gain_value, PcmGain.label(PcmGain.db(volume))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.review_gain_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(thickness = .5.dp, color = LocalGlass.current.border)
            if (editingVoice != null && editingVoice.edit == null) Text(stringResource(R.string.ui_legacy_summary), style = MaterialTheme.typography.bodySmall)
            Slider(value = PcmGain.db(volume).coerceIn(PcmGain.MIN_DB, PcmGain.MAX_DB), onValueChange = { changeVolume(PcmGain.linear(it)) },
                onValueChangeFinished = ::renderReview, valueRange = PcmGain.MIN_DB..PcmGain.MAX_DB, enabled = !working && validReview,
                modifier = Modifier.fillMaxWidth().testTag("edit-volume").semantics { contentDescription = activity.getString(R.string.audio_gain_description) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { changeVolume(1f); renderReview() }, enabled = !working && validReview && (volume != 1f || normalizationTarget != null), modifier = Modifier.testTag("review-gain-reset")) { Text(stringResource(R.string.audio_gain_reset)) }
                FilledTonalButton(onClick = ::normalize, enabled = !working && validReview, modifier = Modifier.testTag("normalize-volume")) { Text(stringResource(R.string.audio_normalize)) }
                OutlinedButton(onClick = ::renderReview, enabled = reviewDirty && !working, modifier = Modifier.testTag("review-gain-apply")) { Text(stringResource(R.string.review_apply_gain)) }
            }
            gainNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            ContextHelp(stringResource(R.string.audio_gain_help) + "\n\n" + stringResource(R.string.audio_conditioning_help))
        }
    }
    internal fun renderReview() {
        if (working || closed || phase != Phase.STOPPED || !validReview) return
        // Rebuild derivatives without removing the review tree or its scroll position.
        preview.close(); working = true; phase = Phase.REVIEW_PROCESSING
        val requested = volume; val target = normalizationTarget; val hasEnhanced = enhancedReady
        worker.execute {
            val result = runCatching {
                val savedPack = if (editingVoice?.edit != null && !editChanged) ModelPackRepository.find(activity, packId) else null
                fun render(source: File, destination: File, after: Boolean): Pair<Float, List<Float>> {
                    check(!closed) { "Processing cancelled" }
                    // An unchanged legacy recipe previews its stored bytes, not a new processing order.
                    val savedVersion = savedPack?.let { ModelPackRepository.recordingSource(it, editingVoice!!, after) }
                    if (savedVersion != null) {
                        savedVersion.copyTo(destination, overwrite = true)
                        val pcm = DeepFilterNet3Denoiser.readRecording(destination)
                        return Math.pow(10.0, editingVoice!!.edit!!.effectiveDb / 20.0).toFloat() to DeepFilterNet3Denoiser.waveform(pcm)
                    }
                    val pcm = DeepFilterNet3Denoiser.readRecording(source)
                    val gain = target?.let {
                        try { minOf(requested, PcmGain.linear(Loudness.adjustment(pcm, it) { closed }.gainDb)) }
                        // Silence has no loudness target; never amplify denoising residue.
                        catch (_: Loudness.SilentInput) { minOf(requested, 1f) }
                    } ?: requested
                    val effective = PcmGain.apply(pcm, gain)
                    PcmWav.Writer(destination).use { it.write(pcm) }
                    return effective to DeepFilterNet3Denoiser.waveform(pcm)
                }
                render(rawTrim, trimmed, false) to if (hasEnhanced) render(rawEnhanced, enhanced, true) else null
            }
            main.post {
                working = false
                if (closed) return@post
                result.onSuccess { (before, after) ->
                    appliedVolume = before.first; beforeLevels = before.second
                    after?.let { enhancedVolume = it.first; afterLevels = it.second }
                    reviewDirty = false
                }.onFailure { reviewDirty = true; gainNotice = activity.getString(R.string.review_processing_failed) }
                phase = Phase.STOPPED
            }
        }
    }
    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun Comparison(after: Boolean) {
        val title = stringResource(if (after) R.string.df_after else if (trimApplied) R.string.trim_before else R.string.df_before)
        val versionFile = if (after) enhanced else beforeSample
        val active = preview.state.key == versionFile.absolutePath
        val duration = (trimEnd - trimStart).coerceAtLeast(0)
        GlassCard(Modifier.fillMaxWidth().testTag(if (after) "denoise-after" else "denoise-before"), selected = useEnhanced == after,
            enabled = !working && !reviewDirty, onSelect = { selectVersion(after) }) {
            Text(title.substringBefore(" · "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (after) LocalGlass.current.success else MaterialTheme.colorScheme.onSurface)
            title.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (useEnhanced == after) StudioBadge(stringResource(R.string.ui_selected_short), active = after)
            HorizontalDivider(thickness = .5.dp, color = LocalGlass.current.border)
            if (after || beforeLevels.isNotEmpty()) PlaybackWaveform(if (after) afterLevels else beforeLevels, 1, (if (after) afterLevels else beforeLevels).size.toLong(),
                (if (active) preview.state.positionMs else 0).toLong(), duration.toLong(), { target ->
                    if (active) preview.seekWithFeedback(target.toInt()) else preview.play(versionFile, title, positionMs = target.toInt(), autoPlay = false, retain = true) { previewError = true }
                }, if (after) "review-waveform-after" else "review-waveform-before", title, height = 72.dp, interactive = !working && !reviewDirty)
            SampleTransport(preview, enabled = !working && !reviewDirty, active = active, duration = duration,
                prefix = if (after) "after" else "before", playTag = if (after) "preview-after" else "preview-before",
                start = { previewError = false; preview.play(versionFile, title, retain = true) { previewError = true } },
                seek = { position -> if (active) preview.seekWithFeedback(position) else preview.play(versionFile, title, positionMs = position, autoPlay = false, retain = true) { previewError = true } }, stop = preview::stop)
            Text("${audioTime(if (active) preview.state.positionMs else 0)} / ${audioTime(duration)}", style = MaterialTheme.typography.labelMedium)
            HorizontalDivider(thickness = .5.dp, color = LocalGlass.current.border)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioButton(selected = useEnhanced == after, onClick = { selectVersion(after) }, enabled = !working && !reviewDirty)
                TextButton(onClick = { selectVersion(after) }, enabled = !working && !reviewDirty, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (after) R.string.df_use_enhanced else if (trimApplied) R.string.trim_use_before else R.string.df_use_original))
                }
            }
        }
    }
    private fun selectVersion(after: Boolean) {
        if (working || reviewDirty || closed || useEnhanced == after || (after && !enhancedReady)) return
        useEnhanced = after
        feedback?.tick()
    }
    private fun save() {
        if (phase != Phase.STOPPED || working || closed || reviewDirty) return
        if (name.isBlank()) { nameError = true; return }
        preview.close(); saveError = false; working = true; phase = Phase.SAVING
        val label = name
        val selectedFile = if (useEnhanced && enhancedReady) enhanced else beforeSample
        val hasEnhanced = enhancedReady
        val recipe = if (trimEnd - trimStart in 3000..30000) VoiceEdit(trimStart, trimEnd,
            PcmGain.db(volume), PcmGain.db(if (useEnhanced && hasEnhanced) enhancedVolume else appliedVolume), useEnhanced && hasEnhanced,
            if (editingVoice != null) editingVoice.edit?.source ?: "edit-base" else "original", normalizationTarget) else null
        worker.execute {
            val result = runCatching {
                check(!closed) { "Saving cancelled" }
                if (editingVoice?.edit != null && !editChanged && useEnhanced == editingVoice.edit.enhanced) {
                    requireNotNull(ModelPackRepository.find(activity, packId)) // Exact no-op preserves current bytes.
                } else if (editingVoice != null) ModelPackRepository.updateVoiceAudio(activity.applicationContext, packId, editingVoice.id,
                    selectedFile, beforeSample, enhanced.takeIf { hasEnhanced }, cancelled = { closed }, edit = recipe, editBase = original)
                else ModelPackRepository.importRecording(activity.applicationContext, packId, label,
                    selectedFile, original = original, enhanced = enhanced.takeIf { hasEnhanced }, trimmed = trimmed.takeIf { validReview && it.isFile },
                    edit = recipe, cancelled = { closed })
            }
            result.exceptionOrNull()?.let { Log.e("PocketTTS", "VOICE_SAVE_FAILED", it) }
            main.post {
                working = false
                if (result.isSuccess) {
                    if (!closed && !activity.isDestroyed) { saved(result.getOrThrow()); feedback?.confirm() }
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
    Canvas(Modifier.fillMaxWidth().height(if (recording) 140.dp else 96.dp)
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerLow)), RoundedCornerShape(18.dp))
        .border(.75.dp, LocalGlass.current.border, RoundedCornerShape(18.dp)).padding(14.dp)
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
