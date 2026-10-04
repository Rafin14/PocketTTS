package org.pockettts.android.engine

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrimSpeedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun capture(name: String) {
        ui.waitForIdle(); SystemClock.sleep(300)
        val folder = File(ui.activity.getExternalFilesDir(null), "trim-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
            File(folder, "$name.png").outputStream().use { output -> it.compress(Bitmap.CompressFormat.PNG, 100, output) }; it.recycle()
        }
    }
    @Test fun longStereoTrimBoundariesFormatsAndShortInput() {
        val app = ui.activity
        val source = File(app.cacheDir, "trim-source-qa.wav")
        val selected = File(app.cacheDir, "trim-selected-qa.wav")
        try {
            // 60 seconds, stereo 48kHz. Distinct retained/discarded amplitudes.
            val rate = 48000; val bytes = rate * 60 * 4
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(bytes + 36); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
                put("data".toByteArray()); putInt(bytes)
            }
            source.outputStream().buffered().use { output ->
                output.write(header.array())
                repeat(60) { second ->
                    val block = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(rate) { block.putShort(if (second in 20..23) 3276 else 26213); block.putShort(if (second in 20..23) 3276 else 26213) }
                    output.write(block.array())
                }
            }
            val originalHash = java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            val info = WavSamples.inspect(source)
            assertEquals(60000, info.durationMs); assertEquals(512, info.peaks.size)
            assertTrue(info.peaks.first() > .7f); assertTrue(info.peaks[180] < .2f)
            WavSamples.trim(source, selected, 20000, 24000)
            val pcm = DeepFilterNet3Denoiser.readRecording(selected)
            assertEquals(96000, pcm.size); assertTrue(pcm.all { it in .07f.. .12f })
            assertArrayEquals(originalHash, java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
            for ((start, end) in listOf(-1 to 4000, 23000 to 22000, 0 to 2000, 0 to 31000, 59000 to 63000))
                assertTrue(runCatching { WavSamples.trim(source, selected, start, end) }.isFailure)
            PcmWav.Writer(source).use { it.write(FloatArray(24000)) }
            assertEquals(1000, WavSamples.inspect(source).durationMs)
            assertTrue(runCatching { WavSamples.trim(source, selected) }.isFailure)
            source.writeText("bad RIFF")
            assertTrue(runCatching { WavSamples.inspect(source) }.isFailure)
        } finally { source.delete(); selected.delete() }
    }
    @Test fun importTrimPreviewEnhanceSaveCancelAndThemes() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity; assumeTrue(app.packs.isNotEmpty())
        val pack = app.packs.first(); val oldSelection = app.selection; val oldTheme = app.appearance
        val source = File(app.cacheDir, "trim-flow-qa.wav")
        PcmWav.Writer(source).use { writer -> repeat(10) { sec -> writer.write(FloatArray(24000) { if (sec in 2..5) .1f else .8f }) } }
        val bytes = source.readBytes(); val name = "Trim QA ${System.currentTimeMillis()}"
        try {
            ui.runOnIdle { app.switchTab(1); app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            val dialog = app.recordingDialog!!
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("trim-range"))
            ui.onNodeWithTag("trim-range").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(10f, center.y), androidx.compose.ui.geometry.Offset(width * .2f, center.y))
            }
            ui.runOnIdle { assertTrue(dialog.trimStart > 0); assertTrue(dialog.trimEnd - dialog.trimStart >= 3000) }
            ui.runOnIdle { dialog.changeTrim(2000, 6000) }
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("trim-range"))
                ui.onNodeWithTag("trim-range").assertIsDisplayed()
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasContentDescription("Start 0.1 seconds earlier"))
                capture("trim-$theme")
            }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("trim-preview"))
            ui.onNodeWithTag("trim-preview").performClick()
            val preview = field(dialog, "preview") as SamplePreview
            ui.waitUntil(15000) { preview.state.playing }
            ui.runOnIdle { assertEquals(4000, preview.state.durationMs) }
            val trimmed = field(dialog, "trimmed") as File
            assertTrue(DeepFilterNet3Denoiser.readRecording(trimmed).all { it in .09f.. .11f })
            ui.waitUntil(10000) { !preview.state.playing && preview.state.positionMs == preview.state.durationMs }
            ui.onNodeWithTag("trim-preview").performClick()
            ui.waitUntil(10000) { preview.state.playing }
            ui.runOnIdle { dialog.changeTrim(2100, 6100); assertNull(preview.state.key); dialog.changeTrim(2000, 6000) }
            ui.onNodeWithTag("trim-apply").performClick()
            ui.waitUntil(120000) { dialog.phase == VoiceRecordingDialog.Phase.STOPPED }
            assertTrue(dialog.enhancedReady)
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-name"))
            ui.onNodeWithTag("record-name").performTextInput(name)
            ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog == null }
            val saved = ModelPackRepository.find(app, pack.id)!!.voices.first { it.displayName == name }
            val archive = File(pack.voicesDir, ".recordings/${saved.id}")
            assertArrayEquals(bytes, File(archive, "original.wav").readBytes())
            assertEquals(44 + 96000 * 2L, File(archive, "trimmed.wav").length())
            assertArrayEquals(File(archive, "enhanced.wav").readBytes(), File(pack.voicesDir, saved.fileName).readBytes())
            assertEquals(File(archive, "trimmed.wav").canonicalFile, ModelPackRepository.recordingSource(pack, saved, false)?.canonicalFile)
            ui.runOnIdle { assertEquals(saved.id, app.selection?.second?.id); app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.close() }
            assertArrayEquals(bytes, source.readBytes())
            // Short audio is explicitly kept without attempting unsupported enhancement.
            PcmWav.Writer(source).use { it.write(FloatArray(24000)) }
            ui.runOnIdle { app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.onNodeWithTag("trim-apply").assertIsNotEnabled()
            ui.runOnIdle { app.recordingDialog!!.applyTrim(true) }
            assertEquals(VoiceRecordingDialog.Phase.STOPPED, app.recordingDialog!!.phase)
            assertFalse(app.recordingDialog!!.enhancedReady)
        } finally {
            ui.runOnIdle { app.recordingDialog?.close(); app.setAppearance(oldTheme) }
            ModelPackRepository.find(app, pack.id)?.voices?.filter { it.displayName == name }?.forEach { ModelPackRepository.deleteVoice(app, pack.id, it.id) }
            ui.runOnIdle { oldSelection?.let { app.selectVoice(it.first, it.second) }; app.switchTab(0) }
            source.delete()
        }
    }
    @Test fun actualPreviewRatePauseSeekAndRestart() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity; val file = File(app.cacheDir, "speed-preview-qa.wav")
        PcmWav.Writer(file).use { out -> repeat(20) { out.write(FloatArray(24000) { kotlin.math.sin(it * .1).toFloat() * .1f }) } }
        val player = app.preview
        try {
            ui.runOnIdle { player.play(file) { error("Preview failed") } }
            ui.waitUntil(10000) { player.state.playing }
            for (rate in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 1.35f)) {
                ui.runOnIdle { player.changeSpeed(rate); player.seekTo(0) }
                SystemClock.sleep(400)
                val start = player.state.positionMs; val time = SystemClock.elapsedRealtime()
                SystemClock.sleep(1400)
                val actual = (player.state.positionMs - start).toFloat() / (SystemClock.elapsedRealtime() - time)
                assertEquals("Preview measured rate $rate", rate, actual, .25f)
                ui.runOnIdle { assertEquals(1f, (field(player, "player") as MediaPlayer).playbackParams.pitch, .001f) }
            }
            ui.runOnIdle { player.toggle(); player.changeSpeed(.65f) }
            val position = player.state.positionMs
            SystemClock.sleep(500)
            ui.runOnIdle { assertFalse(player.state.playing); assertEquals(position, player.state.positionMs); player.toggle() }
            ui.waitUntil(5000) { player.state.playing }
            ui.runOnIdle { assertEquals(.65f, (field(player, "player") as MediaPlayer).playbackParams.speed, .001f); player.close(); player.play(file) { error("Restart failed") } }
            ui.waitUntil(10000) { player.state.playing }
            ui.runOnIdle { assertEquals(.65f, (field(player, "player") as MediaPlayer).playbackParams.speed, .001f) }
        } finally { ui.runOnIdle { player.close(); player.changeSpeed(PlaybackSpeed.read(app)) }; file.delete() }
    }
    @Test fun readerActualMediaRateChunksSeekAndNotification() {
        ui.waitUntil(15000) { ui.activity.documentReady && field(ui.activity, "readerService") != null }
        val app = ui.activity; val service = field(app, "readerService") as ReaderPlaybackService
        val rateBefore = app.speed
        val textBefore = app.editor.text.toString()
        val editingBefore = app.editing
        val appearanceBefore = app.appearance
        val directory = File(app.cacheDir, "reader-rate-qa-${System.nanoTime()}").apply { mkdirs() }
        val files = (0..1).map { index -> File(directory, "$index.wav").also { file ->
            PcmWav.Writer(file).use { out -> repeat(10) { out.write(FloatArray(24000) { kotlin.math.sin(it * .1).toFloat() * .1f }) } }
        } }
        // Supply generated WAV fixtures, exercising the real service/player without
        // invoking Pocket TTS through the emulator's unsupported ARM translator.
        val type = Class.forName("org.pockettts.android.engine.ReaderPlaybackService\$Session")
        val session = type.declaredConstructors.first().apply { isAccessible = true }.newInstance(directory, "Rate QA", 1f, "Two chunks")
        @Suppress("UNCHECKED_CAST") (field(session, "files") as MutableList<File>).addAll(files)
        (field(session, "timeline") as AudioTimeline).apply { append(10000); append(10000) }
        type.getDeclaredField("complete").apply { isAccessible = true }.setBoolean(session, true)
        type.getDeclaredField("total").apply { isAccessible = true }.setInt(session, 2)
        val peaks = files.flatMap { WavSamples.inspect(it).peaks }
        type.getDeclaredField("peaks").apply { isAccessible = true }.set(session, peaks)
        type.getDeclaredField("binSamples").apply { isAccessible = true }.setLong(session, 469)
        type.getDeclaredField("samples").apply { isAccessible = true }.setLong(session, 480000)
        try {
            ui.runOnIdle {
                app.stopReader()
                ReaderPlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }.set(service, session)
                service.resume()
            }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.switchTab(0) }
                capture("compact-reader-playing-$theme")
            }
            ui.runOnIdle { service.pause() }
            ui.onNodeWithTag("reader-seek").performTouchInput { click(Offset(width * .55f, height / 2f)) }
            ui.waitUntil(10000) { app.snapshot.chunk == 2 && app.snapshot.positionMs in 10000..12500 && field(service, "prepared") == true }
            ui.runOnIdle { assertEquals(ReaderPlaybackService.State.PAUSED, app.snapshot.state) }
            ui.onNodeWithTag("reader-seek").performTouchInput {
                swipe(Offset(width * .55f, height / 2f), Offset(width * .2f, height / 2f), 500)
            }
            ui.waitUntil(10000) { app.snapshot.chunk == 1 && app.snapshot.positionMs in 2500..5500 && field(service, "prepared") == true }
            ui.runOnIdle { assertEquals(ReaderPlaybackService.State.PAUSED, app.snapshot.state); service.resume() }
            val media = field(service, "mediaSession") as MediaSession
            for (rate in listOf(1f, 1.25f, .75f, .5f, 1.5f, 2f, 1.15f)) {
                ui.runOnIdle { app.selectSpeed(rate); service.seekTo(0) }
                ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING && field(service, "prepared") == true && field(service, "seeking") == false }
                SystemClock.sleep(350)
                var start = 0; var time = 0L
                ui.runOnIdle { start = (field(service, "player") as MediaPlayer).currentPosition; time = SystemClock.elapsedRealtime() }
                SystemClock.sleep(1400)
                ui.runOnIdle {
                    val audio = field(service, "player") as MediaPlayer
                    val actual = (audio.currentPosition - start).toFloat() / (SystemClock.elapsedRealtime() - time)
                    assertEquals("Reader measured rate $rate", rate, actual, .2f)
                    assertEquals(1f, audio.playbackParams.pitch, .001f)
                    assertEquals(rate, media.controller.playbackState!!.playbackSpeed, .001f)
                }
            }
            ui.runOnIdle { service.pause(); app.selectSpeed(.8f); service.seekTo(11000) }
            ui.waitUntil(10000) { field(service, "prepared") == true }
            SystemClock.sleep(300)
            ui.runOnIdle { assertFalse((field(service, "player") as MediaPlayer).isPlaying); media.controller.transportControls.play() }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { assertEquals(.8f, (field(service, "player") as MediaPlayer).playbackParams.speed, .001f); service.seekTo(9700) }
            ui.waitUntil(10000) { app.snapshot.chunk == 2 && app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { assertEquals(.8f, (field(service, "player") as MediaPlayer).playbackParams.speed, .001f); service.stop(); service.resume() }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { assertEquals(.8f, (field(service, "player") as MediaPlayer).playbackParams.speed, .001f) }
            ui.runOnIdle { app.sheet = "speed" }
            ui.onNodeWithTag("playback-speed").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.25f) }
            ui.runOnIdle { assertEquals(1.25f, app.speed, .001f); assertEquals(1.25f, (field(service, "player") as MediaPlayer).playbackParams.speed, .001f) }
            capture("speed-reader")
            ui.runOnIdle { app.sheet = null }
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(750)
            assertEquals(android.media.session.PlaybackState.STATE_PLAYING, media.controller.playbackState!!.state)
            assertEquals(1.25f, media.controller.playbackState!!.playbackSpeed, .001f)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.runOnIdle { app.setEditing(true, false); assertEquals(ReaderPlaybackService.State.STOPPED, app.snapshot.state); service.resume() }
            ui.waitUntil(10000) { app.snapshot.state == ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle {
                app.editor.append(" Updated document")
                assertEquals(ReaderPlaybackService.State.STOPPED, app.snapshot.state)
                assertEquals(-1, app.snapshot.rangeStart)
            }
        } finally {
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.runOnIdle { app.sheet = null; service.stop(); app.selectSpeed(rateBefore); app.setAppearance(appearanceBefore); app.editor.setText(textBefore); app.setEditing(editingBefore, false) }
            files.forEach(File::delete); directory.delete()
        }
    }
}
