package org.pockettts.android.engine

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class VoiceLimitsUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        val folder = File(ui.activity.getExternalFilesDir(null), "limits-ui-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
            File(folder, "$name.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }; it.recycle()
        }
    }
    @Test fun near256MiBImportThirtyMinuteScanTrimAndCancellation() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity
        val source = File(app.cacheDir, "large-limit-test.wav")
        val target = File(app.cacheDir, "large-limit-trim.wav")
        val bytes = PcmWav.MAX_VOICE_BYTES - 44
        try {
            RandomAccessFile(source, "rw").use {
                it.setLength(bytes + 44); it.write(PcmWav.header(bytes, 48000, 2))
                it.seek(44); it.write(byteArrayOf(0, 64, 0, 64))
            }
            val info = WavSamples.inspect(source)
            assertEquals((bytes * 1000 / (48000 * 4)).toInt(), info.durationMs)
            assertTrue(info.peaks.first() >= .49f)
            assertTrue(runCatching { WavSamples.inspect(source, AtomicBoolean(true)) }.isFailure)
            WavSamples.trim(source, target, info.durationMs - 5000, info.durationMs - 1000)
            assertEquals(4000, WavSamples.inspect(target).durationMs)
            ui.runOnIdle { app.showImportedVoice(BundledPocketTts.ID, Uri.fromFile(source)) }
            // Main-thread work remains possible during the large copy and native scan.
            ui.runOnIdle { assertNotNull(app.recordingDialog) }
            ui.waitUntil(90000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.onNodeWithTag("sheet-close").assertIsDisplayed().performClick()
            ui.waitUntil(10000) { app.recordingDialog == null }
            ui.runOnIdle { app.showImportedVoice(BundledPocketTts.ID, Uri.fromFile(source)) }
            ui.onNodeWithTag("sheet-close").performClick()
            ui.waitUntil(10000) { app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("voice-import-") } }
            RandomAccessFile(source, "rw").use {
                val durationBytes = 24000L * 2 * 1800
                it.setLength(durationBytes + 44); it.seek(0); it.write(PcmWav.header(durationBytes))
            }
            assertEquals(1800000, WavSamples.inspect(source).durationMs)
        } finally { ui.runOnIdle { app.recordingDialog?.close() }; source.delete(); target.delete() }
    }

    @Test fun persistentCloseRetryActionsAndAdjustTrimAcrossThemes() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity; val mode = app.appearance
        val source = File(app.cacheDir, "ui-audit-reference.wav")
        PcmWav.Writer(source).use { out -> repeat(5) { out.write(FloatArray(24000) { kotlin.math.sin(it * .1).toFloat() * .1f }) } }
        val corrupt = File(app.cacheDir, "ui-audit-corrupt.wav").apply { writeText("invalid") }
        fun retryAndCancel(tag: String) {
            ui.onNodeWithTag(tag).performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString() == app.packageName }
            ui.runOnIdle { app.sheet = null }
        }
        try {
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.sheet = "add" }
                ui.onNodeWithTag("sheet-close").assertIsDisplayed()
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("add-video"))
                ui.onNodeWithTag("add-video").assertIsDisplayed(); capture("add-$theme")
                ui.onNodeWithTag("sheet-close").performClick()
                ui.runOnIdle { assertNull(app.sheet); app.showAbout() }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("about-notices"))
                ui.onNodeWithTag("sheet-close").assertIsDisplayed(); capture("about-$theme")
                ui.onNodeWithTag("sheet-close").performClick()
            }
            ui.runOnIdle { app.showImportedVoice(BundledPocketTts.ID, Uri.fromFile(corrupt)) }
            ui.waitUntil(10000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.FAILED }
            ui.onNodeWithTag("import-replace").assertIsDisplayed(); capture("invalid-wav")
            retryAndCancel("import-replace")
            ui.runOnIdle { app.showVideo(BundledPocketTts.ID, Uri.fromFile(corrupt)) }
            ui.waitUntil(10000) { app.videoDialog?.error != null }
            ui.onNodeWithTag("video-replace").assertIsDisplayed(); capture("invalid-video")
            retryAndCancel("video-replace")
            ui.runOnIdle { app.showImportedVoice(BundledPocketTts.ID, Uri.fromFile(source)) }
            ui.waitUntil(10000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.applyTrim(false) }
            ui.waitUntil(120000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("record-save").assertIsDisplayed().performClick()
            ui.onAllNodesWithText(app.getString(R.string.voice_name_required)).onFirst().assertIsDisplayed()
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("adjust-trim"))
            ui.onNodeWithTag("adjust-trim").performClick()
            ui.runOnIdle { assertEquals(VoiceRecordingDialog.Phase.TRIMMING, app.recordingDialog!!.phase) }
            ui.onNodeWithTag("trim-apply").assertIsDisplayed(); capture("adjust-trim")
            ui.onNodeWithTag("sheet-close").performClick()
        } finally { ui.runOnIdle { app.recordingDialog?.close(); app.videoDialog?.close(); app.sheet = null; app.setAppearance(mode) }; source.delete(); corrupt.delete() }
    }
}
