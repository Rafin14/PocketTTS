package org.pockettts.android.engine

import android.graphics.Bitmap
import android.net.Uri
import android.text.style.BackgroundColorSpan
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReaderEnhancementsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        ui.waitForIdle(); android.os.SystemClock.sleep(300)
        val folder = File(ui.activity.getExternalFilesDir(null), "reader-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    // Deterministic UI checks, not a substitute for physical-device native TTS playback.
    @Test fun exactHighlightPauseEditManualScrollAndWaveform() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity
        val original = app.editor.text.toString()
        val text = (1..200).joinToString("\n") { "Line $it: a quiet space for words." }
        val start = text.indexOf("Line 100:")
        val peaks = AudioPeaks().apply { add(FloatArray(24000 * 3) { kotlin.math.sin(it * .1).toFloat() * .3f }) }
        val snapshot = ReaderPlaybackService.Snapshot(ReaderPlaybackService.State.PLAYING, document = text,
            rangeStart = start, rangeEnd = start + 30, waveform = peaks.snapshot(), waveformBinSamples = peaks.binSamples(),
            waveformSamples = peaks.samples, availableMs = 3000, positionMs = 1200, complete = true)
        val listener = MainActivity::class.java.getDeclaredField("readerListener").apply { isAccessible = true }.get(app) as ReaderPlaybackService.Listener
        val serviceField = MainActivity::class.java.getDeclaredField("readerService").apply { isAccessible = true }
        ui.waitUntil(10000) { serviceField.get(app) != null && app.hasWindowFocus() }
        val service = serviceField.get(app) as ReaderPlaybackService
        ui.runOnIdle { service.removeListener(listener) }
        try {
            ui.runOnIdle { app.switchTab(0); app.editor.setText(text); app.readingHighlight.resetFollow(); listener.onReaderStateChanged(snapshot) }
            ui.waitForIdle()
            try { ui.waitUntil(5000) { app.editor.scrollY > 0 } }
            catch (error: Throwable) {
                throw AssertionError("state=${app.snapshot.state} range=${app.snapshot.rangeStart} layout=${app.editor.layout?.height} height=${app.editor.height} spans=${app.editor.text.getSpans(0, app.editor.length(), BackgroundColorSpan::class.java).size}", error)
            }
            ui.runOnIdle {
                val spans = app.editor.text.getSpans(0, text.length, BackgroundColorSpan::class.java)
                assertEquals(1, spans.size); assertEquals(start, app.editor.text.getSpanStart(spans[0]))
                assertTrue(app.editor.scrollY > 0)
                listener.onReaderStateChanged(snapshot.copy(state = ReaderPlaybackService.State.PAUSED))
            }
            screenshot("highlight-waveform")
            ui.runOnIdle { assertEquals(1, app.editor.text.getSpans(0, text.length, BackgroundColorSpan::class.java).size) }
            ui.onNodeWithTag("document").performTouchInput { swipeDown() }
            var scroll = 0
            ui.runOnIdle { scroll = app.editor.scrollY; listener.onReaderStateChanged(snapshot.copy(state = ReaderPlaybackService.State.PAUSED)) }
            ui.waitForIdle()
            ui.runOnIdle {
                assertEquals(scroll, app.editor.scrollY)
                app.editor.append(" edited")
                assertTrue(app.editor.text.getSpans(0, app.editor.length(), BackgroundColorSpan::class.java).isEmpty())
                assertEquals(text + " edited", app.editor.text.toString())
                listener.onReaderStateChanged(snapshot.copy(state = ReaderPlaybackService.State.STOPPED, rangeStart = -1, rangeEnd = -1))
            }
            ui.onNodeWithTag("reader-waveform").assertExists()
        } finally { ui.runOnIdle { listener.onReaderStateChanged(ReaderPlaybackService.Snapshot(ReaderPlaybackService.State.IDLE)); app.editor.setText(original); service.addListener(listener) } }
    }
    @Test fun importedStereoWavEnhanceReviewAndSave() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue(app.packs.isNotEmpty())
        val pack = app.packs.first(); val selection = app.selection
        val source = File(app.cacheDir, "import-stereo-qa.wav")
        writeWav(source, 44100, 2, 16, false)
        val raw = source.readBytes()
        val name = "Import QA ${System.currentTimeMillis()}"
        try {
            ui.runOnIdle { app.switchTab(1); app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("trim-skip"))
            ui.onNodeWithTag("trim-skip").performClick()
            ui.waitUntil(120000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.STOPPED }
            ui.runOnIdle { assertTrue(app.recordingDialog!!.enhancedReady) }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-before"))
            ui.onNodeWithTag("preview-before").performClick()
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-after"))
            ui.onNodeWithTag("preview-after").performClick()
            screenshot("import-review")
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-name"))
            ui.onNodeWithTag("record-name").performTextInput(name)
            ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(15000) { app.recordingDialog == null && app.selection?.second?.displayName == name }
            val voice = app.selection!!.second
            assertArrayEquals(raw, source.readBytes())
            assertArrayEquals(raw, ModelPackRepository.recordingSource(pack, voice, false)!!.readBytes())
            val enhanced = ModelPackRepository.recordingSource(pack, voice, true)!!
            assertEquals(72000, DeepFilterNet3Denoiser.readRecording(enhanced).size)
            assertArrayEquals(enhanced.readBytes(), File(pack.voicesDir, voice.fileName).readBytes())
        } finally {
            ui.runOnIdle { app.recordingDialog?.close(); app.stopPreview() }
            ModelPackRepository.find(app, pack.id)?.voices?.filter { it.displayName == name }?.forEach { ModelPackRepository.deleteVoice(app, pack.id, it.id) }
            ui.runOnIdle { selection?.let { app.selectVoice(it.first, it.second) } }
            source.delete()
        }
    }
    @Test fun wavFormatsNormalizeWithoutMutatingSource() {
        val app = ui.activity
        val source = File(app.cacheDir, "formats-qa.wav"); val normalized = File(app.cacheDir, "normalized-qa.wav")
        try {
            DeepFilterNet3Denoiser(app).use { engine ->
                for ((rate, channels, bits, float) in listOf(Format(16000, 1, 8, false), Format(44100, 2, 24, false), Format(48000, 2, 32, true), Format(96000, 2, 64, true))) {
                    writeWav(source, rate, channels, bits, float)
                    val original = source.readBytes()
                    engine.normalizeImported(source, normalized)
                    assertEquals(72000, DeepFilterNet3Denoiser.readRecording(normalized).size)
                    assertArrayEquals(original, source.readBytes())
                }
                source.writeText("invalid")
                assertTrue(runCatching { engine.normalizeImported(source, normalized) }.isFailure)
            }
        } finally { source.delete(); normalized.delete() }
    }
    @Test fun adaptiveIconAndKeyboardReturnToOriginalLayout() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity
        val icon = app.packageManager.getApplicationIcon(app.packageName)
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        val folder = File(app.getExternalFilesDir(null), "reader-qa").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(17, 18, 22) }
        canvas.drawRect(200f, 0f, 400f, 160f, paint)
        for (x in listOf(20, 220)) { icon.setBounds(x, 30, x + 96, 126); icon.draw(canvas) }
        File(folder, "launcher.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val keyboard = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        var height = 0
        try {
            ui.runOnIdle { app.setEditing(true, false); app.switchTab(0) }
            ui.waitForIdle(); android.os.SystemClock.sleep(500)
            ui.runOnIdle { height = app.editor.height }
            repeat(3) {
                ui.onNodeWithTag("document").performTouchInput { click(center) }
                ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                screenshot("ime-open-$it")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
                ui.waitForIdle(); android.os.SystemClock.sleep(500)
                ui.runOnIdle { assertEquals(height, app.editor.height) }
                screenshot("ime-closed-$it")
            }
            ui.activityRule.scenario.recreate()
            ui.waitUntil(15000) { ui.activity.documentReady }
            ui.onNodeWithTag("document").assertIsDisplayed()
        } finally { shell("settings put secure show_ime_with_hard_keyboard $keyboard") }
    }
    private data class Format(val rate: Int, val channels: Int, val bits: Int, val float: Boolean)
    @Test fun corruptImportRejectedAndLongOriginalRetained() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity; assumeTrue(app.packs.isNotEmpty())
        val pack = app.packs.first(); val source = File(app.cacheDir, "invalid-import-qa.wav")
        try {
            source.writeText("not a WAV")
            ui.runOnIdle { app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(15000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.FAILED }
            ui.onNodeWithTag("record-save").assertDoesNotExist()
            ui.runOnIdle { app.recordingDialog?.close() }
            PcmWav.Writer(source).use { writer -> repeat(31) { writer.write(FloatArray(PcmWav.RATE)) } }
            val bytes = source.readBytes()
            ui.runOnIdle { app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.applyTrim(true) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.STOPPED }
            ui.runOnIdle { assertFalse(app.recordingDialog!!.enhancedReady) }
            ui.onNodeWithTag("record-save").assertIsEnabled()
            assertArrayEquals(bytes, source.readBytes())
        } finally { ui.runOnIdle { app.recordingDialog?.close() }; source.delete() }
    }
    private fun writeWav(file: File, rate: Int, channels: Int, bits: Int, float: Boolean) {
        val frames = rate * 3; val align = channels * bits / 8; val bytes = frames * align
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(if (float) 3 else 1); putShort(channels.toShort()); putInt(rate); putInt(rate * align)
            putShort(align.toShort()); putShort(bits.toShort()); put("data".toByteArray()); putInt(bytes)
        }
        file.outputStream().buffered().use { output ->
            output.write(header.array())
            val frame = ByteBuffer.allocate(align).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { i ->
                frame.clear()
                repeat(channels) {
                    val value = kotlin.math.sin(i * 2 * Math.PI * 220 / rate) * .2
                    when {
                        float && bits == 64 -> frame.putDouble(value)
                        float -> frame.putFloat(value.toFloat())
                        bits == 8 -> frame.put((128 + value * 127).toInt().toByte())
                        bits == 16 -> frame.putShort((value * 32767).toInt().toShort())
                        else -> { val v = (value * 8388607).toInt(); frame.put(v.toByte()); frame.put((v shr 8).toByte()); frame.put((v shr 16).toByte()) }
                    }
                }
                output.write(frame.array())
            }
        }
    }
}
