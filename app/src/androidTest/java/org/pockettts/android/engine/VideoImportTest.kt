package org.pockettts.android.engine

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.CancellationSignal
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class VideoImportTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun fixture(name: String): File = File(ui.activity.cacheDir, name).also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("video/$name").use { input -> file.outputStream().use(input::copyTo) }
    }
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun capture(name: String) {
        ui.waitForIdle()
        val folder = File(ui.activity.getExternalFilesDir(null), "theme-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
            File(folder, "$name.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }; it.recycle()
        }
    }

    @Test fun decodeErrorsCancellationAndRealPcm() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity
        val normal = fixture("speech-test.mp4"); val silent = fixture("silent-test.mp4"); val short = fixture("short-test.mp4")
        val corrupt = File(app.cacheDir, "corrupt-video.mp4").apply { writeText("not a video") }
        val target = File(app.cacheDir, "video-decoder-test.wav")
        try {
            VideoAudioExtractor.extract(app, Uri.fromFile(normal), target, CancellationSignal())
            val info = WavSamples.inspect(target)
            assertTrue(info.durationMs in 7900..8200); assertTrue(info.peaks.any { it > .01f })
            assertTrue(PcmWav.isVoiceSample(target)); target.delete()
            for (file in listOf(silent, short, corrupt)) {
                val error = runCatching { VideoAudioExtractor.extract(app, Uri.fromFile(file), target, CancellationSignal()) }.exceptionOrNull()
                assertNotNull(error); assertFalse(target.exists())
                if (file == silent) assertTrue(error!!.message!!.contains("no audio"))
                if (file == short) assertTrue(error!!.message!!.contains("3 seconds"))
            }
            val signal = CancellationSignal().apply { cancel() }
            assertTrue(runCatching { VideoAudioExtractor.extract(app, Uri.fromFile(normal), target, signal) }.isFailure)
            assertFalse(target.exists())
        } finally { listOf(normal, silent, short, corrupt, target).forEach { it.delete() } }
    }

    @Test fun nearThirtyMinuteDecodeAndOverDurationRejection() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity
        val source = fixture("long-test.mp4")
        val excessive = fixture("over-duration-test.mp4")
        val target = File(app.cacheDir, "long-video-test.wav")
        try {
            assertTrue(VideoAudioExtractor.inspect(app, Uri.fromFile(source), CancellationSignal()).durationMs in 1798000..1800000)
            VideoAudioExtractor.extract(app, Uri.fromFile(source), target, CancellationSignal())
            assertTrue(WavSamples.inspect(target).durationMs in 1798000..1800000)
            assertTrue(target.length() < PcmWav.MAX_VOICE_BYTES)
            target.delete()
            assertEquals(PcmWav.DURATION_ERROR, runCatching {
                VideoAudioExtractor.inspect(app, Uri.fromFile(excessive), CancellationSignal())
            }.exceptionOrNull()?.message)
            assertEquals(PcmWav.DURATION_ERROR, runCatching {
                VideoAudioExtractor.extract(app, Uri.fromFile(excessive), target, CancellationSignal())
            }.exceptionOrNull()?.message)
            assertFalse(target.exists())
        } finally { listOf(source, excessive, target).forEach { it.delete() } }
    }

    @Test fun videoPreviewTrimEnhanceSaveAndCleanup() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity; val original = app.selection; val mode = app.appearance
        val video = fixture("speech-test.mp4"); val name = "Video QA ${System.currentTimeMillis()}"
        var extracted: File? = null
        try {
            ui.runOnIdle { app.switchTab(1); app.showVideo(BundledPocketTts.ID, Uri.fromFile(video)) }
            ui.waitUntil(15000) { app.videoDialog?.player?.state?.let { it.key != null && !it.loading } == true }
            val dialog = app.videoDialog!!; val player = dialog.player
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }; capture("video-$theme")
            }
            ui.runOnIdle { player.toggle() }
            ui.waitUntil(5000) { player.state.playing && player.state.positionMs > 100 }
            ui.runOnIdle { player.toggle(); player.seekTo(4000) }
            ui.waitUntil(5000) { player.state.positionMs in 3700..4300 }
            ui.runOnIdle {
                assertEquals(4000f, (field(player, "player") as MediaPlayer).currentPosition.toFloat(), 400f)
                assertFalse(player.state.playing)
                dialog.extract()
            }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            assertNull(app.videoDialog)
            val recording = app.recordingDialog!!
            extracted = field(recording, "extracted") as File
            assertTrue(WavSamples.inspect(extracted!!).peaks.any { it > .01f })
            val preview = field(recording, "preview") as SamplePreview
            ui.runOnIdle { recording.changeTrim(1000, 6000); recording.seekTrim(3000) }
            ui.waitUntil(15000) { preview.state.key != null && !preview.state.loading && preview.state.positionMs in 1800..2200 }
            ui.runOnIdle { preview.toggle() }
            ui.waitUntil(5000) { preview.state.playing }
            capture("video-trim")
            ui.runOnIdle { recording.applyTrim(false) }
            ui.waitUntil(120000) { recording.phase == VoiceRecordingDialog.Phase.STOPPED }
            assertTrue(recording.enhancedReady)
            val trimmed = field(recording, "trimmed") as File
            assertEquals(5000, WavSamples.inspect(trimmed).durationMs)
            for (tag in listOf("preview-before", "preview-after")) {
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag(tag))
                ui.onNodeWithTag(tag).performScrollTo().performClick()
                ui.waitUntil(5000) { preview.state.playing }
            }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-name"))
            ui.onNodeWithTag("record-name").performScrollTo().performTextInput(name)
            ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(15000) { app.recordingDialog == null && app.selection?.second?.displayName == name }
            val (pack, voice) = app.selection!!
            val source = File(pack.voicesDir, ".recordings/${voice.id}/original.wav")
            val enhanced = ModelPackRepository.recordingSource(pack, voice, true)!!
            assertTrue(WavSamples.inspect(source).durationMs >= 7900)
            assertEquals(5000, WavSamples.inspect(enhanced).durationMs)
            assertArrayEquals(enhanced.readBytes(), File(pack.voicesDir, voice.fileName).readBytes())
            ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${voice.id}"))
            ui.onNodeWithTag("voice-${voice.id}").assertIsSelected()
            ui.waitUntil(5000) { !extracted!!.exists() }
        } finally {
            ui.runOnIdle { app.videoDialog?.close(); app.recordingDialog?.close(); app.setAppearance(mode) }
            ModelPackRepository.find(app, BundledPocketTts.ID)?.voices?.filter { it.displayName == name }?.forEach { ModelPackRepository.deleteVoice(app, BundledPocketTts.ID, it.id) }
            ui.runOnIdle { original?.let { app.selectVoice(it.first, it.second) } }
            video.delete()
        }
    }

    @Test fun cancelPreviewExtractionAndTrim() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity; val video = fixture("speech-test.mp4")
        try {
            repeat(3) { stage ->
                ui.runOnIdle { app.showVideo(BundledPocketTts.ID, Uri.fromFile(video)) }
                ui.waitUntil(15000) { app.videoDialog?.info != null }
                val dialog = app.videoDialog!!; val file = field(dialog, "file") as File
                if (stage == 0) ui.runOnIdle { dialog.close() }
                if (stage == 1) ui.runOnIdle { dialog.extract(); dialog.close() }
                if (stage == 2) {
                    ui.runOnIdle { dialog.extract() }
                    ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
                    ui.runOnIdle { app.recordingDialog!!.close() }
                }
                ui.waitUntil(5000) { app.videoDialog == null && app.recordingDialog == null && !file.exists() }
                assertNull(dialog.player.state.key)
            }
        } finally { ui.runOnIdle { app.videoDialog?.close(); app.recordingDialog?.close() }; video.delete() }
    }

    @Test fun systemPickerSelectCancelAndBackgroundCleanup() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val title = "Pocket-video-picker-${System.nanoTime()}.mp4"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, title)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("video/speech-test.mp4").use { input ->
                app.contentResolver.openOutputStream(uri)!!.use(input::copyTo)
            }
            values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            app.contentResolver.update(uri, values, null, null)
            ui.runOnIdle { app.pickVideo(app.packs.first()) }
            ui.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000) { app.sheet == "add" }
            ui.runOnIdle { app.pickVideo(app.packs.first()) }
            ui.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            var node: android.view.accessibility.AccessibilityNodeInfo? = null
            ui.waitUntil(10000) {
                // Avoid the thumbnail's "Preview <filename>" action, which opens Photos.
                node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)?.firstOrNull { it.text?.toString() == title }
                node != null
            }
            while (node != null && !node!!.isClickable) node = node!!.parent
            assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(15000) { app.videoDialog?.info != null }
            val dialog = app.videoDialog!!
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertNull(app.videoDialog); assertNull(dialog.player.state.key)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        } finally {
            app.contentResolver.delete(uri, null, null)
            ui.runOnIdle { app.videoDialog?.close(); app.sheet = null }
        }
    }

    @Test fun videoDerivedReferenceSynthesizesOnArm64() {
        org.junit.Assume.assumeTrue("Native Pocket TTS requires ARM64 hardware; x86 translation is unsupported.",
            !android.os.Build.SUPPORTED_ABIS.first().startsWith("x86"))
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity; val previous = app.selection
        val video = fixture("speech-test.mp4")
        val original = File(app.cacheDir, "video-native-original.wav")
        val trimmed = File(app.cacheDir, "video-native-trim.wav")
        val enhanced = File(app.cacheDir, "video-native-enhanced.wav")
        var voice: PackVoice? = null
        try {
            VideoAudioExtractor.extract(app, Uri.fromFile(video), original, CancellationSignal())
            WavSamples.trim(original, trimmed, 0, 6000)
            DeepFilterNet3Denoiser(app).use { it.processWav(trimmed, enhanced) }
            val pack = ModelPackRepository.importRecording(app, BundledPocketTts.ID, "Video native QA", enhanced, original, enhanced, trimmed)
            voice = pack.voices.last()
            var frames = 0L
            assertTrue(PocketEngine.withEngine(app, pack) { engine ->
                engine.synthesize("This voice came from a local video.", voice!!.fileName, object : NativePocketTts.AudioSink {
                    override fun onAudio(audio: FloatArray): Boolean { assertTrue(audio.all { it.isFinite() }); frames += audio.size; return true }
                })
            })
            assertTrue(frames > 2400)
        } finally {
            voice?.let { ModelPackRepository.deleteVoice(app, BundledPocketTts.ID, it.id) }
            previous?.let { ModelPackRepository.selectVoice(app, it.first.id, it.second.id) }
            listOf(video, original, trimmed, enhanced).forEach { it.delete() }
        }
    }
}
