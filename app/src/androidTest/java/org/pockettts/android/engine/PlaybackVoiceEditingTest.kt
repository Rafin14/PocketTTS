package org.pockettts.android.engine

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaybackVoiceEditingTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun ready() = ui.waitUntil(30000) { ui.activity.documentReady && field(ui.activity, "readerService") != null }
    private fun capture(name: String) {
        ui.waitForIdle()
        val folder = File(ui.activity.getExternalFilesDir(null), "playback-edit-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    private fun verifyNativeReference(app: MainActivity, packId: String, voice: PackVoice) {
        if (android.os.Build.SUPPORTED_ABIS.first().startsWith("x86")) return
        var samples = 0
        assertTrue(PocketEngine.withEngine(app, ModelPackRepository.find(app, packId)!!) { engine ->
            engine.synthesize("Hello from this voice.", voice.fileName, object : NativePocketTts.AudioSink {
                override fun onAudio(audio: FloatArray): Boolean { samples += audio.size; return true }
            })
        }); assertTrue(samples > 0)
    }

    @Test fun pendingChunksPausedNavigationStaleCallbacksAndCompletion() {
        ready()
        val app = ui.activity
        val service = field(app, "readerService") as ReaderPlaybackService
        val originalText = app.editor.text.toString()
        val directory = File(app.cacheDir, "chunk-navigation-qa-${System.nanoTime()}").apply { mkdirs() }
        val files = (0..2).map { i -> File(directory, "$i.wav").also { file ->
            PcmWav.Writer(file).use { out -> repeat(3) { out.write(FloatArray(24000) { kotlin.math.sin(it * .1).toFloat() * .05f }) } }
        } }
        val type = Class.forName("org.pockettts.android.engine.ReaderPlaybackService\$Session")
        val text = "First chunk. Second chunk. Third chunk."
        val session = type.declaredConstructors.first().apply { isAccessible = true }.newInstance(directory, "Navigation QA", 1f, text)
        @Suppress("UNCHECKED_CAST") val ranges = field(session, "ranges") as MutableList<ReaderTextChunker.Range>
        ranges.addAll(listOf(ReaderTextChunker.Range(0, 12), ReaderTextChunker.Range(13, 26), ReaderTextChunker.Range(27, text.length)))
        type.getDeclaredField("total").apply { isAccessible = true }.setInt(session, 3)
        val accept = ReaderPlaybackService::class.java.declaredMethods.first { it.name == "acceptChunk" }.apply { isAccessible = true }
        val completion = ReaderPlaybackService::class.java.declaredMethods.first { it.name == "completeChunk" }.apply { isAccessible = true }
        fun generated(i: Int) { accept.invoke(service, session, files[i], 3000L, listOf(.1f), 72000L, 72000L * (i + 1)) }
        try {
            ui.runOnIdle {
                service.discardAudio(); app.editor.setText(text)
                ReaderPlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }.set(service, session)
                service.resume()
                service.nextChunk(); service.nextChunk(); service.nextChunk()
                assertEquals(3, app.snapshot.chunk); assertEquals(3, app.snapshot.chunks)
                assertFalse(app.snapshot.canNext); assertEquals(27, app.snapshot.rangeStart)
                service.pause(); generated(0); generated(1)
                assertNull(field(service, "player")); assertEquals(ReaderPlaybackService.State.PAUSED, app.snapshot.state)
                generated(2)
                type.getDeclaredField("complete").apply { isAccessible = true }.setBoolean(session, true)
            }
            ui.waitUntil(10000) { field(service, "prepared") == true }
            ui.runOnIdle {
                assertFalse((field(service, "player") as MediaPlayer).isPlaying)
                service.previousChunk(); service.previousChunk()
            }
            ui.waitUntil(10000) { field(service, "prepared") == true && app.snapshot.chunk == 1 }
            ui.runOnIdle { assertFalse(app.snapshot.canPrevious); service.previousChunk(); service.resume() }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            val media = field(service, "mediaSession") as MediaSession
            var old: MediaPlayer? = null
            ui.runOnIdle {
                old = field(service, "player") as MediaPlayer
                service.nextChunk()
                completion.invoke(service, session, old, 0) // Delayed callback from the released first chunk.
                assertEquals(2, app.snapshot.chunk)
            }
            ui.waitUntil(10000) { app.snapshot.chunk == 2 && app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle {
                assertEquals(13, app.snapshot.rangeStart)
                assertEquals(PlaybackState.STATE_PLAYING, media.controller.playbackState!!.state)
                media.controller.transportControls.skipToNext()
            }
            ui.waitUntil(10000) { app.snapshot.chunk == 3 && app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { service.seekTo(7400) }
            ui.waitUntil(10000) { app.snapshot.chunk == 3 && field(service, "prepared") == true && field(service, "seeking") == false }
            ui.runOnIdle {
                val staleThird = field(service, "player") as MediaPlayer
                service.previousChunk()
                completion.invoke(service, session, staleThird, 2)
                assertEquals(2, app.snapshot.chunk)
            }
            ui.waitUntil(10000) { app.snapshot.chunk == 2 && app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { service.previousChunk() }
            ui.waitUntil(10000) { app.snapshot.chunk == 1 && app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { service.pause(); service.seekTo(3500) }
            ui.waitUntil(10000) { field(service, "prepared") == true && field(service, "seeking") == false }
            ui.runOnIdle {
                service.previousChunk() // Offset must not turn Previous into Restart current.
                assertEquals(1, app.snapshot.chunk); assertEquals(ReaderPlaybackService.State.PAUSED, app.snapshot.state)
            }
            ui.waitUntil(10000) { field(service, "prepared") == true }
            ui.runOnIdle { service.seekTo(2850); service.resume() }
            ui.waitUntil(10000) { app.snapshot.chunk == 2 && app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { service.seekTo(8850) }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.IDLE }
            ui.runOnIdle {
                assertFalse(app.snapshot.canNext); assertFalse(app.snapshot.canPrevious)
                service.stop(); generated(0)
                assertEquals(3, (field(session, "files") as List<*>).size)
            }
            capture("reader-compact")
        } finally { ui.runOnIdle { service.discardAudio(); app.editor.setText(originalText) }; files.forEach(File::delete); directory.delete() }
    }

    @Test fun renameEditDenoiseRestartAndRestoreKeepVoiceIdentity() {
        ready()
        var app = ui.activity
        val originalSelection = app.selection
        val originalMode = app.appearance
        val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val initialCount = pack.voices.size
        val source = File(app.cacheDir, "voice-edit-qa-${System.nanoTime()}.wav")
        WavSamples.trim(File(pack.voicesDir, "alba.wav"), source, 0, 8000)
        val bytes = source.readBytes()
        var voice = ModelPackRepository.importRecording(app, pack.id, "Edit QA ${System.nanoTime()}", source).voices.last()
        val duplicate = ModelPackRepository.importRecording(app, pack.id, "Duplicate QA ${System.nanoTime()}", source).voices.last()
        val renamed = "Renamed QA ${System.nanoTime()}"
        fun current() = ModelPackRepository.find(ui.activity, pack.id)!!.voices.first { it.id == voice.id }
        try {
            assertTrue(runCatching { ModelPackRepository.renameVoice(app, pack.id, voice.id, "  ") }.isFailure)
            assertTrue(runCatching { ModelPackRepository.renameVoice(app, pack.id, voice.id, duplicate.displayName) }.isFailure)
            ui.runOnIdle { app.selectVoice(pack, voice); app.switchTab(1) }
            ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-actions-${voice.id}"))
            ui.onNodeWithTag("voice-actions-${voice.id}").performClick()
            ui.onNodeWithText("Rename").performClick()
            ui.onNodeWithTag("rename-name").performTextReplacement(renamed)
            capture("rename")
            ui.onNodeWithTag("rename-save").performClick()
            ui.waitUntil(15000) { app.renameTarget == null && app.selection?.second?.displayName == renamed }
            assertArrayEquals(bytes, File(pack.voicesDir, current().fileName).readBytes())
            voice = current()
            ui.runOnIdle { app.editVoice(pack, voice) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.changeTrim(1000, 5000) }
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }
                ui.onNodeWithTag("trim-apply").assertIsDisplayed()
                capture("edit-$theme")
            }
            ui.onNodeWithTag("trim-apply").performClick()
            ui.waitUntil(120000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.STOPPED }
            ui.runOnIdle { assertTrue(app.recordingDialog!!.enhancedReady) }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-after"))
            ui.onNodeWithTag("preview-after").performClick()
            capture("edit-denoised")
            ui.onNodeWithTag("record-name").assertDoesNotExist()
            ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog == null }
            voice = current()
            assertEquals(renamed, voice.displayName); assertTrue(voice.audioEdited)
            assertEquals(initialCount + 2, ModelPackRepository.find(app, pack.id)!!.voices.size)
            assertEquals(4000, WavSamples.inspect(File(pack.voicesDir, voice.fileName)).durationMs)
            assertArrayEquals(bytes, ModelPackRepository.originalSource(pack, voice).readBytes())
            assertArrayEquals(ModelPackRepository.recordingSource(pack, voice, true)!!.readBytes(), File(pack.voicesDir, voice.fileName).readBytes())
            val editedPath = voice.fileName
            assertTrue(runCatching { ModelPackRepository.updateVoiceAudio(app, pack.id, voice.id, source, cancelled = { true }) }.isFailure)
            assertEquals(editedPath, current().fileName)
            verifyNativeReference(app, pack.id, voice)
            ui.activityRule.scenario.recreate(); ready(); app = ui.activity
            ui.runOnIdle { assertEquals(voice.id, app.selection!!.second.id); assertEquals(editedPath, app.selection!!.second.fileName); app.previewVoice(pack, voice) }
            ui.waitUntil(10000) { app.preview.state.playing }
            ui.onNodeWithTag("preview-mini").assertDoesNotExist()
            ui.onNodeWithTag("preview-toggle").assertIsDisplayed()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("preview-waveform").fetchSemanticsNodes().isNotEmpty() }
            ui.runOnIdle { app.preview.seekTo(1000); app.preview.toggle() }
            ui.runOnIdle { app.stopPreview(); app.restoreVoice(pack, voice) }
            ui.onNodeWithText("Restore", useUnmergedTree = true).performClick()
            ui.waitUntil(15000) { !app.busy && app.selection?.second?.audioEdited == false }
            voice = current()
            assertEquals(renamed, voice.displayName); assertFalse(voice.audioEdited)
            assertArrayEquals(bytes, File(pack.voicesDir, voice.fileName).readBytes())
            assertEquals(initialCount + 2, ModelPackRepository.find(app, pack.id)!!.voices.size)
            assertFalse(File(pack.voicesDir, editedPath).exists())
            assertNull(ModelPackRepository.recordingSource(pack, voice, true))
            ui.activityRule.scenario.recreate(); ready(); app = ui.activity
            ui.runOnIdle { assertEquals(voice.id, app.selection!!.second.id); assertFalse(app.selection!!.second.audioEdited) }
            verifyNativeReference(app, pack.id, voice)
            val restoredPath = voice.fileName
            ui.runOnIdle { app.editVoice(pack, voice) }
            ui.waitUntil(15000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            val cancelledDialog = app.recordingDialog!!
            val temporary = listOf("importedOriginal", "trimmed", "enhanced").map { field(cancelledDialog, it) as File }
            ui.runOnIdle { cancelledDialog.applyTrim(false); cancelledDialog.close() }
            ui.waitUntil(15000) { temporary.none { it.exists() } }
            assertEquals(restoredPath, current().fileName)
            assertArrayEquals(bytes, File(pack.voicesDir, current().fileName).readBytes())
        } finally {
            app = ui.activity
            ui.runOnIdle { app.recordingDialog?.close(); app.stopPreview(); app.renameTarget = null; app.confirmation = null; app.setAppearance(originalMode) }
            listOf(voice.id, duplicate.id).forEach { ModelPackRepository.deleteVoice(app, pack.id, it) }
            ui.runOnIdle { originalSelection?.let { app.selectVoice(it.first, it.second) } }
            source.delete()
        }
    }

    @Test fun realGenerationKeyboardPlayAndNavigation() {
        assumeTrue("Native Pocket synthesis requires ARM64.", !android.os.Build.SUPPORTED_ABIS.first().startsWith("x86"))
        ready()
        val app = ui.activity
        val service = field(app, "readerService") as ReaderPlaybackService
        val original = app.editor.text.toString(); val editing = app.editing
        val selection = app.selection
        val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val params = listOf(ModelPackRepository.temperature(app, pack), ModelPackRepository.lsdSteps(app, pack),
            ModelPackRepository.threads(app, pack), ModelPackRepository.sentencePauseMs(app, pack), ModelPackRepository.maxTextTokens(app, pack))
        val text = List(4) { "This is a short offline test. We are checking chunk navigation." }.joinToString("\n\n")
        val chunks = ReaderTextChunker.ranges(text, 120).toList()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val keyboard = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            ModelPackRepository.saveParameters(app, pack, params[0] as Float, params[1] as Int, params[2] as Int, params[3] as Int, 10)
            shell("settings put secure show_ime_with_hard_keyboard 1")
            ui.runOnIdle { service.discardAudio(); app.switchTab(0); app.selectVoice(pack, pack.voices.first { it.id == "alba" }); app.editor.setText(text); app.setEditing(true, false) }
            ui.onNodeWithTag("document").performTouchInput { click(center) }
            ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            ui.onNodeWithText(app.getString(R.string.reader_play)).performClick()
            ui.runOnIdle {
                assertTrue(app.editor.hasFocus()); assertEquals(chunks.size, app.snapshot.chunks)
                service.nextChunk(); service.nextChunk(); service.pause()
                assertEquals(3, app.snapshot.chunk); assertEquals(chunks[2].start, app.snapshot.rangeStart)
            }
            ui.waitUntil(240000) { app.snapshot.complete || app.snapshot.state == ReaderPlaybackService.State.ERROR }
            ui.runOnIdle { assertTrue(app.snapshot.message, app.snapshot.complete); assertEquals(ReaderPlaybackService.State.PAUSED, app.snapshot.state) }
            ui.onNodeWithText(app.getString(R.string.reader_resume)).performClick()
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            capture("native-reader-keyboard")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
            ui.runOnIdle { service.pause(); service.nextChunk() }
            ui.waitUntil(10000) { field(service, "prepared") == true }
            ui.runOnIdle { assertEquals(chunks.size, app.snapshot.chunk); assertFalse(app.snapshot.canNext); assertEquals(ReaderPlaybackService.State.PAUSED, app.snapshot.state); service.resume() }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            capture("native-reader-playing")
            ui.runOnIdle { service.seekTo(app.snapshot.availableMs - 200) }
            ui.waitUntil(15000) { app.snapshot.state == ReaderPlaybackService.State.IDLE }
        } finally {
            ui.runOnIdle { service.discardAudio(); app.editor.setText(original); app.setEditing(editing, false); selection?.let { app.selectVoice(it.first, it.second) } }
            shell("settings put secure show_ime_with_hard_keyboard $keyboard")
            ModelPackRepository.saveParameters(app, pack, params[0] as Float, params[1] as Int, params[2] as Int, params[3] as Int, params[4] as Int)
        }
    }

    @Test fun compactLandscapeReaderAndEditingSheets() {
        ready()
        val orientation = ui.activity.requestedOrientation
        val mode = ui.activity.appearance
        val original = ui.activity.editor.text.toString()
        val selected = ui.activity.selection
        val pack = ModelPackRepository.find(ui.activity, BundledPocketTts.ID)!!
        val source = File(ui.activity.cacheDir, "landscape-edit-qa-${System.nanoTime()}.wav")
        WavSamples.trim(File(pack.voicesDir, "alba.wav"), source, 0, 8000)
        val voice = ModelPackRepository.importRecording(ui.activity, pack.id, "Landscape QA ${System.nanoTime()}", source).voices.last()
        try {
            ui.runOnIdle { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(15000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && ui.activity.documentReady }
            ui.runOnIdle { ui.activity.switchTab(0); ui.activity.setEditing(false); ui.activity.editor.setText("A long document remains scrollable.\n".repeat(5000)) }
            ui.onNodeWithTag("reader-scroll").performScrollToNode(hasTestTag("reader-next"))
            ui.onNodeWithTag("reader-next").assertIsDisplayed()
            ui.onNodeWithTag("reader-previous").assertIsDisplayed()
            ui.onNodeWithText(ui.activity.getString(R.string.reader_play)).assertIsDisplayed()
            capture("reader-landscape")
            ui.runOnIdle { ui.activity.editVoice(pack, voice) }
            ui.waitUntil(15000) { ui.activity.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { ui.activity.setAppearance(theme) }
                ui.onNodeWithTag("sheet-close").assertIsDisplayed()
                ui.onNodeWithTag("trim-apply").assertIsDisplayed()
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("trim-range"))
                ui.onNodeWithTag("trim-range").assertIsDisplayed()
                capture("edit-landscape-$theme")
            }
            ui.onNodeWithTag("sheet-close").performClick()
            ui.waitUntil(10000) { ui.activity.recordingDialog == null }
        } finally {
            ui.runOnIdle { ui.activity.recordingDialog?.close(); ui.activity.editor.setText(original); ui.activity.setAppearance(mode); ui.activity.requestedOrientation = orientation }
            ModelPackRepository.deleteVoice(ui.activity, pack.id, voice.id)
            ui.waitUntil(15000) { ui.activity.documentReady }
            ui.runOnIdle { selected?.let { ui.activity.selectVoice(it.first, it.second) } }
            source.delete()
        }
    }
}
