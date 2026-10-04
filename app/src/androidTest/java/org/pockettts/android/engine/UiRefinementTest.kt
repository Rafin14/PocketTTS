package org.pockettts.android.engine

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiRefinementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun ready() = ui.waitUntil(60000) { ui.activity.documentReady }
    private fun capture(name: String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5000)
        android.os.SystemClock.sleep(400) // Let the native popup window render before the screenshot.
        val folder = File(ui.activity.getExternalFilesDir(null), "refinement-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
            File(folder, "$name.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }; it.recycle()
        }
    }
    private fun shortDrags() {
        ui.waitForIdle()
        ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("glass-sheet").assertIsDisplayed(); true }.getOrDefault(false) }
        val px = ui.activity.resources.displayMetrics.density
        for (duration in listOf(600L, 45L)) {
            ui.onNodeWithTag("sheet-handle", useUnmergedTree = true).performTouchInput {
                swipe(center, center + Offset(0f, 80 * px), duration)
            }
            ui.waitForIdle()
            ui.onNodeWithTag("glass-sheet").assertIsDisplayed()
        }
    }
    private fun dismissDrag() {
        val distance = ui.onNodeWithTag("sheet-content").fetchSemanticsNode().boundsInRoot.height * .7f
        ui.onNodeWithTag("sheet-handle", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, distance), 650)
        }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("glass-sheet").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun sharedSheetsSmallDragScrollAndDeliberateDismiss() {
        ready(); val app = ui.activity; val mode = app.appearance
        val pack = app.packs.first { it.id == BundledPocketTts.ID }; val voice = pack.voices.first()
        try {
            ui.runOnIdle { app.switchTab(1) }
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.sheet = "add" }
                shortDrags()
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("add-video"))
                ui.onNodeWithTag("add-video").assertIsDisplayed()
                capture("add-$theme")
                ui.onNodeWithTag("sheet-content").performTouchInput { swipe(center, center + Offset(0f, 80f), 45) }
                ui.waitForIdle(); ui.onNodeWithTag("sheet-content").assertIsDisplayed()
                shortDrags(); dismissDrag(); assertNull(app.sheet)
            }
            ui.runOnIdle { app.detail = pack to voice }; shortDrags(); capture("details"); dismissDrag(); assertNull(app.detail)
            ui.runOnIdle { app.addVoice(pack, true) }; shortDrags(); dismissDrag(); assertNull(app.recordingDialog)
            ui.runOnIdle { app.previewVoice(pack, voice) }
            ui.waitUntil(10000) { app.preview.state.playing }; shortDrags()
            ui.onNodeWithTag("preview-toggle").performClick(); ui.runOnIdle { assertFalse(app.preview.state.playing) }
            ui.onNodeWithTag("preview-waveform").performTouchInput { click(center) }
            shortDrags(); dismissDrag(); assertNull(app.preview.state.key)
        } finally { ui.runOnIdle { app.sheet = null; app.detail = null; app.recordingDialog?.close(); app.stopPreview(); app.setAppearance(mode) } }
    }

    @Test fun menusAndReaderBalanceAcrossThemes() {
        ready(); val app = ui.activity; val mode = app.appearance; val text = app.editor.text.toString()
        val pack = app.packs.first { it.id == BundledPocketTts.ID }
        val source = File(app.cacheDir, "menu-qa-${System.nanoTime()}.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(120000) { i -> kotlin.math.sin(i * .1).toFloat() * .1f }) }
        val voice = ModelPackRepository.importRecording(app, pack.id, "Menu test ${System.nanoTime()}", source).voices.last()
        try {
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.selectVoice(pack, voice); app.switchTab(0); app.setEditing(false); app.editor.setText("A taller Reader with compact controls.\n".repeat(300)) }
                val document = ui.onNodeWithTag("document").fetchSemanticsNode().boundsInRoot
                val player = ui.onNodeWithTag("playback").fetchSemanticsNode().boundsInRoot
                assertTrue("Text should dominate portrait", document.height > player.height * 1.5f)
                assertTrue("Player should be compact", player.height / app.resources.displayMetrics.density < 235f)
                ui.onNodeWithTag("reader-seek").assertExists(); capture("reader-$theme")
                ui.runOnIdle { app.switchTab(1) }
                ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-actions-${voice.id}"))
                ui.onNodeWithTag("voice-actions-${voice.id}").performClick()
                ui.onNodeWithText("Rename").assertIsDisplayed(); ui.onNodeWithText("Edit audio").assertIsDisplayed()
                ui.onNodeWithText(app.getString(R.string.voice_delete)).assertIsDisplayed()
                ui.onNodeWithText("Save .wav to device").assertIsDisplayed(); capture("menu-$theme")
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ui.waitForIdle()
            }
        } finally {
            ModelPackRepository.deleteVoice(app, pack.id, voice.id); source.delete()
            ui.runOnIdle { app.setAppearance(mode); app.editor.setText(text) }
        }
    }
}
