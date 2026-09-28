package org.pockettts.android.engine

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assume.assumeTrue

class ThemeSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun navigationThemesSheetsAndLargeDocument() {
        ui.waitUntil(15_000) { ui.activity.documentReady }
        val app = ui.activity
        var original = ""
        var mode = "system"
        ui.runOnIdle { original = app.editor.text.toString(); mode = app.appearance }
        try {
            for (theme in listOf("dark", "light", "amoled", "system")) {
                ui.runOnIdle { app.setAppearance(theme) }
                ui.onNodeWithTag("document").assertIsDisplayed()
                ui.onNodeWithTag("playback").assertIsDisplayed()
                capture("reader-$theme")
                ui.onNodeWithTag("nav-1").performClick()
                ui.onNodeWithText(app.getString(R.string.voice_add)).assertIsDisplayed()
                if (theme == "dark") capture("voices-dark")
                ui.onNodeWithTag("nav-2").performClick()
                if (theme == "dark") capture("settings-dark")
                ui.runOnIdle { app.sheet = "appearance" }
                ui.onAllNodesWithText(app.getString(R.string.reader_appearance_amoled)).onLast().assertIsDisplayed()
                if (theme == "dark") capture("appearance-dark")
                ui.runOnIdle { app.sheet = null }
                ui.onNodeWithTag("nav-0").performClick()
            }
            val document = "A large offline reading document. Another sentence for scrolling.\n".repeat(4000)
            ui.runOnIdle { app.editor.setText(document); app.editor.setSelection(document.length) }
            ui.waitForIdle()
            ui.runOnIdle { assertEquals(document.length, app.editor.length()); app.editor.scrollTo(0, app.editor.layout.height) }
            capture("large-document")
            ui.onNodeWithTag("nav-1").performClick()
            ui.onNodeWithTag("nav-0").performClick()
            ui.runOnIdle { assertEquals(document, app.editor.text.toString()) }
        } finally {
            ui.runOnIdle { app.editor.setText(original); app.setAppearance(mode); app.sheet = null }
        }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        // WindowManager dialog/IME animations run outside Compose's test clock.
        android.os.SystemClock.sleep(500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val target = File(ui.activity.getExternalFilesDir(null), "theme-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(target, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun populatedVoicesRecordingEngineAndKeyboard() {
        ui.waitUntil(15_000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue("Install a real model pack to exercise voice/recording screens", app.packs.isNotEmpty())
        val pack = app.packs.first()
        val previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        var original = ""
        var mode = "system"
        ui.runOnIdle { original = app.editor.text.toString(); mode = app.appearance; app.setAppearance("dark"); app.selectPack(pack); app.switchTab(1) }
        try {
            capture("voices-populated")
            ui.onAllNodesWithText(app.getString(R.string.ui_details)).onFirst().performClick()
            capture("voice-details")
            ui.runOnIdle { app.detail = null; app.switchTab(2); app.sheet = "engine" }
            capture("engine-settings")
            ui.runOnIdle { app.sheet = null }
            ui.onAllNodes(hasText("Import language pack", substring = true)).assertCountEquals(0)
            ui.runOnIdle { app.sheet = "add" }
            capture("add-voice")
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
            ui.runOnIdle { app.addVoice(pack, true) }
            capture("recording")
            // Real microphone updates continue independently of Compose's test clock.
            ui.mainClock.autoAdvance = false
            ui.onNodeWithText(app.getString(R.string.voice_record)).performClick()
            android.os.SystemClock.sleep(3500)
            ui.mainClock.advanceTimeBy(100)
            capture("recording-active")
            ui.onNodeWithText(app.getString(R.string.reader_stop)).performClick()
            ui.mainClock.autoAdvance = true
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.applyTrim(true) }
            ui.waitUntil(120_000) { ui.onAllNodesWithText(app.getString(R.string.voice_record_ready)).fetchSemanticsNodes().isNotEmpty() }
            capture("recording-ready")
            ui.runOnIdle { app.recordingDialog?.close(); app.switchTab(0); app.setEditing(true, false); app.editor.setText("A quiet space for your words.\n\nRead offline, at your own pace.") }
            ui.waitUntil(5000) { app.hasWindowFocus() }
            ui.onNodeWithTag("document").performTouchInput { click(center) }
            ui.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            capture("reader-keyboard")
        } finally {
            ui.mainClock.autoAdvance = true
            shell("settings put secure show_ime_with_hard_keyboard $previousKeyboard")
            ui.runOnIdle { app.recordingDialog?.close(); app.editor.setText(original); app.setAppearance(mode); app.sheet = null; app.detail = null; app.confirmation = null; app.switchTab(0) }
        }
    }
    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    @Test fun longVoiceList() {
        ui.waitUntil(15_000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue("Requires an installed pack", app.packs.any { it.voices.isNotEmpty() })
        val pack = app.packs.first { it.voices.isNotEmpty() }
        val added = mutableListOf<String>()
        val prefix = "UI test " + java.util.UUID.randomUUID().toString().take(8)
        val reference = File(app.cacheDir, "$prefix.wav")
        File(pack.voicesDir, pack.voices.first().fileName).copyTo(reference)
        try {
            for (i in 1..30) {
                val updated = ModelPackRepository.importRecording(app, pack.id, "$prefix $i", reference)
                added += updated.voices.last().id
            }
            ui.runOnIdle { app.selectPack(pack); app.switchTab(1) }
            ui.onNodeWithTag("voices-list").performScrollToIndex(30)
            capture("long-voice-list")
            ui.onNodeWithText("$prefix 30").assertExists()
            ui.onNodeWithTag("voices-list").performScrollToIndex(0)
        } finally {
            added.forEach { ModelPackRepository.deleteVoice(app, pack.id, it) }
            reference.delete()
            ui.runOnIdle { app.selectPack(pack); app.switchTab(0) }
        }
    }

    @Test fun playbackAndBackground() {
        assumeTrue("Native synthesis requires a physical ARM64 device; the x86 native bridge SIGILLs in ONNX.",
            !android.os.Build.SUPPORTED_ABIS.first().startsWith("x86"))
        ui.waitUntil(15_000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue("Requires an installed real model and ARM64 support", app.selection != null)
        var original = ""
        var mode = "system"
        val previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        ui.runOnIdle {
            original = app.editor.text.toString(); mode = app.appearance
            app.setAppearance("dark")
            app.setEditing(true, false)
            app.editor.setText("Hello. This is Pocket TTS. Reading stays on your device. ".repeat(8))
        }
        try {
            ui.onNodeWithTag("document").performTouchInput { click(center) }
            ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            ui.runOnIdle {
                app.editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())?.setComposingText(" More words.", 1)
            }
            ui.onNodeWithText(app.getString(R.string.reader_play)).performClick()
            ui.runOnIdle {
                assertTrue(app.editor.hasFocus())
                repeat(3) { app.pauseReader(); app.startReaderPlayback() }
            }
            capture("playback-loading")
            ui.waitUntil(120_000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING || app.snapshot.state == ReaderPlaybackService.State.ERROR }
            assertEquals(app.snapshot.message, ReaderPlaybackService.State.PLAYING, app.snapshot.state)
            assertTrue(app.snapshot.rangeStart >= 0 && app.snapshot.rangeEnd > app.snapshot.rangeStart)
            assertTrue(app.snapshot.waveform.isNotEmpty() && app.snapshot.waveform.size <= 512)
            assertEquals(app.editor.text.toString(), app.snapshot.document)
            ui.runOnIdle { app.pauseReader() }
            val highlighted = app.snapshot.rangeStart
            ui.runOnIdle { assertEquals(highlighted, app.snapshot.rangeStart); app.seekReader(0) }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PAUSED && app.snapshot.rangeStart == 0 }
            ui.runOnIdle { app.startReaderPlayback() }
            ui.waitUntil(120000) { app.snapshot.chunk >= 2 && app.snapshot.rangeStart > 0 }
            capture("playback-playing")
            ui.runOnIdle {
                app.editor.append(" Editing remains available during playback.")
                assertEquals(ReaderPlaybackService.State.STOPPED, app.snapshot.state)
                assertEquals(-1, app.snapshot.rangeStart)
                app.startReaderPlayback()
                androidx.core.view.WindowCompat.getInsetsController(app.window, app.editor).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            ui.waitUntil(120000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING || app.snapshot.state == ReaderPlaybackService.State.ERROR }
            assertEquals(ReaderPlaybackService.State.PLAYING, app.snapshot.state)
            ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
            ui.onNodeWithTag("document").performTouchInput { click(center) }
            ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            android.os.SystemClock.sleep(1000)
            val media = shell("dumpsys media_session")
            assertTrue("Reader session must survive backgrounding", media.contains("PocketTtsReader") &&
                (media.contains("state=3") || media.contains("state=PLAYING(3)")))
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.runOnIdle { app.pauseReader() }
            ui.waitUntil(5000) { app.snapshot.state == ReaderPlaybackService.State.PAUSED }
            capture("playback-paused")
            ui.runOnIdle { app.stopReader() }
            assertEquals(-1, app.snapshot.rangeStart)
            assertTrue(app.snapshot.waveform.isEmpty())
            capture("playback-stopped")
        } finally {
            shell("settings put secure show_ime_with_hard_keyboard $previousKeyboard")
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.runOnIdle { app.stopReader(); app.editor.setText(original); app.setAppearance(mode) }
        }
    }
}
