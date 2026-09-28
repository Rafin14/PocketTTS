package org.pockettts.android.engine

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class VoiceWorkflowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        val folder = File(ui.activity.getExternalFilesDir(null), "theme-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
            File(folder, "$name.png").outputStream().use { stream -> it.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            it.recycle()
        }
    }
    @Test fun selectedVoiceAndSingleExpandablePreview() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue(app.packs.isNotEmpty())
        val pack = app.packs.first()
        val original = app.selection
        val mode = app.appearance
        val sample = File(app.cacheDir, "preview-qa.wav")
        PcmWav.Writer(sample).use { output ->
            repeat(20) { output.write(FloatArray(PcmWav.RATE) { (kotlin.math.sin(it * 2 * Math.PI * 220 / PcmWav.RATE) * .02).toFloat() }) }
        }
        val voices = mutableListOf<PackVoice>()
        try {
            repeat(2) { voices += ModelPackRepository.importRecording(app, pack.id, "Preview QA $it", sample).voices.last() }
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.selectVoice(pack, voices.first()); app.switchTab(1) }
                ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${voices.first().id}"))
                ui.onNodeWithTag("voice-${voices.first().id}").assertIsSelected()
                capture("selected-$theme")
                ui.runOnIdle { app.selectVoice(pack, voices.last()) }
                ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${voices.last().id}"))
                ui.onNodeWithTag("voice-${voices.last().id}").assertIsSelected()
                ui.runOnIdle { assertEquals(voices.last().id, ModelPackRepository.resolveVoice(app, null)?.second?.id) }
            }
            ui.runOnIdle { app.setAppearance("dark"); app.previewVoice(pack, voices.first()) }
            ui.waitUntil(10000) { app.preview.state.playing }
            ui.onNodeWithTag("preview-mini").assertIsDisplayed()
            ui.waitUntil(5000) { app.preview.state.positionMs > 0 }
            capture("preview-mini")
            ui.onNodeWithTag("preview-toggle").performClick()
            ui.runOnIdle { assertFalse(app.preview.state.playing) }
            ui.onNodeWithTag("preview-expand").performClick()
            capture("preview-expanded")
            ui.onAllNodesWithTag("preview-toggle").onLast().performClick()
            ui.waitUntil(5000) { app.preview.state.playing }
            ui.runOnIdle { app.previewExpanded = false; app.previewVoice(pack, voices.last()) }
            ui.waitUntil(10000) { app.preview.state.playing && app.preview.state.title == voices.last().displayName }
            ui.onNodeWithTag("preview-mini").performTouchInput { swipeUp() }
            ui.waitUntil(5000) { app.previewExpanded }
            ui.runOnIdle { app.previewExpanded = false }
            ui.onNodeWithTag("preview-stop").performClick()
            ui.onNodeWithTag("preview-mini").assertDoesNotExist()
            ui.runOnIdle { app.previewVoice(pack, voices.first()) }
            ui.waitUntil(10000) { app.preview.state.playing }
            ui.runOnIdle { app.preview.seekTo(app.preview.state.durationMs - 100) }
            ui.waitUntil(5000) { app.preview.state.key == null }
            ui.onNodeWithTag("preview-mini").assertDoesNotExist()
        } finally {
            ui.runOnIdle { app.stopPreview(); app.setAppearance(mode) }
            voices.forEach { ModelPackRepository.deleteVoice(app, pack.id, it.id) }
            sample.delete()
            ui.runOnIdle { original?.let { app.selectVoice(it.first, it.second) }; app.switchTab(0) }
        }
    }
    @Test fun recordPreviewRerecordSaveWavAndSelect() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue(app.packs.isNotEmpty())
        val pack = app.packs.first()
        val original = app.selection
        val name = "Recording QA ${System.currentTimeMillis()}"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        try {
            ui.runOnIdle { app.switchTab(1); app.sheet = "add" }
            capture("add-voice-polished")
            ui.onAllNodesWithText(app.getString(R.string.voice_record)).onFirst().assertExists()
            ui.runOnIdle { app.addVoice(pack, true) }
            ui.runOnIdle { app.recordingDialog!!.onPermissionResult(false) }
            ui.onNodeWithText(app.getString(R.string.voice_permission_denied)).assertExists()
            ui.runOnIdle { app.recordingDialog!!.onPermissionResult(true) }
            repeat(2) {
                ui.waitForIdle()
                ui.mainClock.autoAdvance = false
                ui.onNodeWithTag("record-control").performClick()
                ui.waitUntil(10000) { app.recordingDialog!!.elapsedMs >= 3500 }
                ui.mainClock.advanceTimeBy(100)
                ui.runOnIdle {
                    assertEquals(48, app.recordingDialog!!.levels.size)
                    assertTrue(app.recordingDialog!!.levels.all { it in 0f..1f })
                }
                capture("recording-waveform-$it")
                ui.onNodeWithTag("record-control").performClick()
                ui.waitUntil(30000) { app.recordingDialog!!.phase == VoiceRecordingDialog.Phase.TRIMMING }
                ui.runOnIdle {
                    if (it == 1) app.recordingDialog!!.let { dialog -> dialog.changeTrim(100, dialog.trimEnd) }
                    app.recordingDialog!!.applyTrim(it == 0)
                }
                ui.waitUntil(120000) { app.recordingDialog!!.phase == VoiceRecordingDialog.Phase.STOPPED }
                ui.mainClock.autoAdvance = true
                ui.runOnIdle { assertTrue(app.recordingDialog!!.enhancedReady) }
                val duration = app.recordingDialog!!.elapsedMs
                android.os.SystemClock.sleep(150)
                ui.runOnIdle { assertEquals(duration, app.recordingDialog!!.elapsedMs) }
                if (it == 0) {
                    ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-before"))
                    ui.onNodeWithTag("preview-before").performScrollTo().performClick()
                    ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-after"))
                    ui.onNodeWithTag("preview-after").performScrollTo().performClick()
                    ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-stop"))
                    ui.waitUntil(5000) { ui.onAllNodesWithTag("preview-stop").fetchSemanticsNodes().isNotEmpty() }
                    ui.onNodeWithTag("preview-stop").performScrollTo().performClick()
                    ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-control"))
                }
            }
            capture("recording-review")
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("denoise-after"))
            capture("denoise-after")
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-name"))
            ui.onNodeWithTag("record-name").performScrollTo().performTextInput(name)
            capture("recording-name")
            capture("recording-save")
            ui.onNodeWithTag("record-save").assertIsDisplayed().performClick()
            try {
                ui.waitUntil(15000) { app.recordingDialog == null && app.selection?.second?.displayName == name }
            } catch (failure: Throwable) {
                capture("recording-save-failed")
                throw AssertionError("phase=${app.recordingDialog?.phase}, selected=${app.selection?.second?.displayName}", failure)
            }
            val voice = app.selection!!.second
            assertTrue(PcmWav.isVoiceSample(File(pack.voicesDir, voice.fileName)))
            assertTrue(voice.fileName.endsWith(".wav"))
            val raw = ModelPackRepository.recordingSource(pack, voice, false)!!
            val enhanced = ModelPackRepository.recordingSource(pack, voice, true)!!
            assertTrue(PcmWav.isVoiceSample(raw))
            assertTrue(PcmWav.isVoiceSample(enhanced))
            assertArrayEquals(enhanced.readBytes(), File(pack.voicesDir, voice.fileName).readBytes())
            ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${voice.id}"))
            ui.onNodeWithTag("voice-${voice.id}").assertIsSelected()
            capture("recording-saved")
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnIdle { app.recordingDialog?.close(); app.stopPreview(); app.sheet = null }
            ModelPackRepository.find(app, pack.id)?.voices?.filter { it.displayName == name }?.forEach {
                ModelPackRepository.deleteVoice(app, pack.id, it.id)
            }
            ui.runOnIdle { original?.let { app.selectVoice(it.first, it.second) }; app.switchTab(0) }
        }
    }
}
