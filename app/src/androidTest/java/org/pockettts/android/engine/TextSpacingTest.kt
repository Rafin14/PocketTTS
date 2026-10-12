package org.pockettts.android.engine

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Presentation fixtures only: no claim of native inference on the emulator. */
class TextSpacingTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?, target: Any = ui.activity) {
        val field = target.javaClass.getDeclaredField(name + "\u0024delegate").apply { isAccessible = true }
        (field.get(target) as androidx.compose.runtime.MutableState<Any?>).value = value
    }
    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(1000); ui.waitForIdle(); SystemClock.sleep(600)
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("stage", "after")!!
        require(stage in listOf("before", "after"))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(ui.activity.getExternalFilesDir(null), "text-spacing/$stage").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val r = ui.activity.resources
        File(dir, "$name.json").writeText(org.json.JSONObject().put("theme", ui.activity.appearance)
            .put("fontScale", r.configuration.fontScale).put("densityDpi", r.displayMetrics.densityDpi)
            .put("width", bitmap.width).put("height", bitmap.height).put("fixture", true).toString())
        bitmap.recycle()
    }
    @Test fun loadingAndNoticeText() {
        ui.waitUntil(60000) { ui.activity.documentReady }
        val app = ui.activity
        check(app.packageName.endsWith(".qa"))
        val theme = app.appearance; val tab = app.tab; val snapshot = app.snapshot
        val busy = app.busy; val notice = app.notice; val error = app.noticeError
        try {
            for (mode in listOf("dark", "light", "amoled")) {
                ui.runOnIdle {
                    app.setAppearance(mode); app.switchTab(2); state("busy", true); state("noticeError", false)
                    state("notice", "Preparing your voice backup. Please keep this screen open until processing finishes.")
                    app.sheet = "engine"
                }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(app.notice))
                capture("$mode-engine-processing-fixture")
                if (InstrumentationRegistry.getArguments().getString("stage", "after") == "after") {
                    val progress = ui.onAllNodesWithTag("notice-progress", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
                    val message = ui.onAllNodesWithTag("notice-message", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
                    assertTrue("Progress must not touch its message", message.top - progress.bottom >= 7f * app.resources.displayMetrics.density)
                }
                if (app.resources.configuration.fontScale > 1.3f && InstrumentationRegistry.getArguments().getString("stage", "after") == "after") {
                    for (index in app.parameters.indices) {
                        ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("engine-field-$index"))
                        val label = ui.onNodeWithTag("engine-label-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        val field = ui.onNodeWithTag("engine-field-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        assertTrue("Large engine label must stay above its field", field.top - label.bottom >= 7f * app.resources.displayMetrics.density)
                    }
                    capture("$mode-engine-large-labels")
                }
                ui.runOnIdle { app.sheet = null; state("busy", false) }
            }
            // Isolate the status component from real service publications on an x86 emulator.
            for (mode in listOf("dark", "light", "amoled")) {
                ui.runOnIdle {
                    app.setAppearance(mode)
                    app.setContent {
                        PocketTheme(mode) {
                            GlassCard(Modifier.padding(24.dp)) {
                                SectionTitle("Playback status — presentation fixture")
                                PlaybackStatus(ReaderPlaybackService.Snapshot(ReaderPlaybackService.State.LOADING,
                                    message = "Preparing the selected voice. Longer documents are generated in sections.", exporting = true))
                            }
                        }
                    }
                }
                capture("$mode-reader-loading-fixture")
                if (InstrumentationRegistry.getArguments().getString("stage", "after") == "after") {
                    val label = ui.onNodeWithTag("playback-status-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val message = ui.onNodeWithTag("playback-status-message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertTrue("Status and explanatory text need a gap", message.top - label.bottom >= 3f * app.resources.displayMetrics.density)
                }
            }
        } finally {
            ui.runOnIdle { app.sheet = null; state("snapshot", snapshot); state("busy", busy); state("notice", notice)
                state("noticeError", error); app.setAppearance(theme); app.switchTab(tab) }
        }
    }
    @Test fun recordingProcessingSheets() {
        ui.waitUntil(60000) { ui.activity.documentReady }
        val app = ui.activity; check(app.packageName.endsWith(".qa"))
        val theme = app.appearance
        val dialog = VoiceRecordingDialog(app, BundledPocketTts.ID, {}, {}, {})
        try {
            for (mode in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(mode); app.setContent { PocketTheme(mode) { dialog.Content() } } }
                for (phase in listOf(VoiceRecordingDialog.Phase.PROCESSING, VoiceRecordingDialog.Phase.DENOISING, VoiceRecordingDialog.Phase.SAVING)) {
                    ui.runOnIdle { state("phase", phase, dialog); state("working", true, dialog) }
                    ui.onNodeWithTag("record-status").assertIsDisplayed()
                    capture("$mode-record-${phase.name.lowercase()}-fixture")
                }
            }
        } finally { ui.runOnIdle { dialog.close(); app.setAppearance(theme) } }
    }
}
