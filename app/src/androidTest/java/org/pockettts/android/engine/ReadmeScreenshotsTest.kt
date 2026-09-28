package org.pockettts.android.engine

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real UI/sample/enhancement; no simulated speech-generation or playback state. */
class ReadmeScreenshotsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle(); android.os.SystemClock.sleep(1200); ui.waitForIdle()
        val folder = File(ui.activity.getExternalFilesDir(null), "readme-screenshots").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun captureCurrentScreens() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity
        val text = app.editor.text.toString(); val mode = app.appearance; val previous = app.selection
        val source = File(app.cacheDir, "readme-demo.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("video/speech-test.mp4").use { input -> source.outputStream().use(input::copyTo) }
        try {
            ui.runOnIdle {
                app.setAppearance("dark"); app.switchTab(0); app.setEditing(false)
                app.editor.setText("A voice for every story\n\nTurn a quiet moment into time to listen. Pocket TTS reads your words on your device, without sending them to a server.\n\nBring an article, a chapter, or an idea. Choose a voice, settle in, and let the story unfold.")
                val pack = app.packs.first { it.id == BundledPocketTts.ID }
                app.selectVoice(pack, pack.voices.first { !it.userCreated })
            }
            capture("reader")
            ui.runOnIdle { app.switchTab(1) }; capture("voices")
            ui.runOnIdle { app.sheet = "add" }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("add-video")); capture("add-voice")
            ui.onNodeWithTag("sheet-close").performClick()
            ui.runOnIdle { app.addVoice(app.packs.first { it.id == BundledPocketTts.ID }, true) }; capture("record-voice")
            ui.runOnIdle { app.recordingDialog?.close(); app.showVideo(BundledPocketTts.ID, Uri.fromFile(source)) }
            ui.waitUntil(15000) { app.videoDialog?.info != null && app.videoDialog?.player?.state?.loading == false }
            capture("video-extraction")
            ui.runOnIdle { app.videoDialog!!.extract() }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasText("Trim sample")); capture("audio-trimming")
            ui.runOnIdle { app.recordingDialog!!.applyTrim(false) }
            ui.waitUntil(120000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-after")); capture("voice-enhancement")
            ui.runOnIdle { app.recordingDialog!!.close(); app.switchTab(2) }; capture("settings")
            ui.runOnIdle { app.showAbout() }; capture("about")
        } finally {
            ui.runOnIdle {
                app.recordingDialog?.close(); app.videoDialog?.close(); app.sheet = null
                app.editor.setText(text); app.setAppearance(mode); previous?.let { app.selectVoice(it.first, it.second) }
            }
            source.delete()
        }
    }
}
