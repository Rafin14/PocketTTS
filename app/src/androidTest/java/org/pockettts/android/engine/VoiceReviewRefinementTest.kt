package org.pockettts.android.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class VoiceReviewRefinementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun ready() { ui.waitUntil(60000) { ui.activity.documentReady }; check(ui.activity.packageName.endsWith(".qa")) }
    private fun field(target: Any, name: String): Any? = try {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    } catch (_: NoSuchFieldException) {
        (target.javaClass.getDeclaredField(name + "\u0024delegate").apply { isAccessible = true }.get(target) as androidx.compose.runtime.State<*>).value
    }
    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(1000); ui.waitForIdle(); SystemClock.sleep(500)
        val app = ui.activity
        val dir = File(app.getExternalFilesDir(null), "denoise-detail").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        File(dir,"$name.json").writeText(org.json.JSONObject().put("theme",app.appearance)
            .put("fontScale",app.resources.configuration.fontScale).put("densityDpi",app.resources.displayMetrics.densityDpi)
            .put("width",bitmap.width).put("height",bitmap.height).toString())
        bitmap.recycle()
    }
    private fun source() = File(ui.activity.cacheDir,"review-refinement-${System.nanoTime()}.wav").also { file ->
        PcmWav.Writer(file).use { out -> repeat(24) { out.write(FloatArray(PcmWav.RATE) { (.05 * kotlin.math.sin(it * .058) + .01 * kotlin.math.sin(it * .017)).toFloat() }) } }
    }
    private fun scroll(tag: String) = ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag(tag))
    @Test fun skipAfterReviewKeepsFullOriginal() {
        ready(); val app=ui.activity; val pack=app.selection!!.first; val previous=app.selection
        val source=File(app.cacheDir,"skip-original-${System.nanoTime()}.wav")
        PcmWav.Writer(source).use { out -> repeat(45) { out.write(FloatArray(PcmWav.RATE) { kotlin.math.sin(it*.08).toFloat()*.05f }) } }
        val bytes=source.readBytes(); var savedId:String?=null
        try {
            ui.runOnIdle { app.showImportedVoice(pack.id,Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            val dialog=app.recordingDialog!!
            ui.runOnIdle { dialog.changeTrim(0,4000); dialog.applyTrim(false) }
            ui.waitUntil(60000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.runOnIdle { dialog.adjustTrim(); dialog.applyTrim(true) }
            ui.waitUntil(30000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            scroll("edit-volume"); ui.onNodeWithTag("edit-volume").assertIsNotEnabled()
            scroll("record-name"); val name="Full original QA ${System.nanoTime()}"
            ui.onNodeWithTag("record-name").performTextInput(name); ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog==null }
            val updated=ModelPackRepository.find(app,pack.id)!!; val voice=updated.voices.first { it.displayName==name }; savedId=voice.id
            assertArrayEquals(bytes,File(updated.voicesDir,voice.fileName).readBytes()); assertNull(voice.edit)
            assertArrayEquals(bytes,ModelPackRepository.originalSource(updated,voice).readBytes())
        } finally {
            ui.runOnIdle { app.recordingDialog?.close() }; savedId?.let { ModelPackRepository.deleteVoice(app,pack.id,it) }; source.delete()
            ui.runOnIdle { previous?.let { app.selectVoice(it.first,it.second) } }
        }
    }
    @Test fun cardSelectionAndRealTransport() {
        ready(); val app = ui.activity; val pack = app.selection!!.first; val previous = app.selection; val theme = app.appearance
        val source = source(); val voices = (0..1).map { ModelPackRepository.importRecording(app,pack.id,"Card QA ${System.nanoTime()}",source).voices.last() }
        try {
            ui.runOnIdle { app.selectVoice(pack,voices.last()); app.selectVoice(pack,voices.first()); app.switchTab(1) }
            ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${voices.last().id}"))
            ui.onNodeWithTag("voice-actions-${voices.last().id}").performClick()
            ui.runOnIdle { assertEquals(voices.first().id,app.selection!!.second.id) }
            capture("voice-menu-no-selection")
            ui.onNodeWithText(app.getString(R.string.voice_rename_action)).performClick()
            ui.runOnIdle { assertEquals(voices.first().id,app.selection!!.second.id); app.renameTarget=null }
            ui.onNodeWithTag("voice-${voices.last().id}").performTouchInput { click(Offset(72f,72f)) }
            ui.onNodeWithText(app.getString(R.string.voice_use)).assertDoesNotExist()
            ui.runOnIdle { assertEquals(voices.last().id,app.selection!!.second.id); assertEquals(voices.last().id,ModelPackRepository.resolveVoice(app,null)!!.second.id); assertNull(app.preview.state.key) }
            for (mode in listOf("dark","light","amoled")) { ui.runOnIdle { app.setAppearance(mode) }; capture("$mode-card-selection") }
            ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-preview-${voices.first().id}"))
            ui.onNodeWithTag("voice-preview-${voices.first().id}").performTouchInput { click() }
            ui.waitUntil(10000) { app.preview.state.playing }
            ui.runOnIdle { assertEquals(voices.last().id,app.selection!!.second.id); app.stopPreview() }
            ui.runOnIdle { app.preview.play(source, "Transport QA", autoPlay=false, retain=true) {} }
            ui.waitUntil(10000) { !app.preview.state.loading && app.preview.state.durationMs > 0 }
            ui.onNodeWithTag("preview-forward").performClick()
            ui.waitUntil(10000) { app.preview.state.positionMs in 4850..5150 }
            ui.runOnIdle { assertFalse(app.preview.state.playing) }
            ui.onNodeWithTag("preview-back").performClick(); ui.waitUntil(10000) { app.preview.state.positionMs < 150 }
            ui.onNodeWithTag("preview-toggle").performClick(); ui.waitUntil(10000) { app.preview.state.playing }
            ui.onNodeWithTag("preview-forward").performClick(); ui.waitUntil(10000) { app.preview.state.positionMs >= 4850 && app.preview.state.playing }
            capture("compact-preview-playing")
            ui.onNodeWithTag("preview-toggle").performClick(); ui.runOnIdle { assertFalse(app.preview.state.playing) }
            ui.runOnIdle { app.preview.seekTo(23900); app.preview.seekBy(5000) }
            ui.waitUntil(10000) { app.preview.state.positionMs >= 23800 }
            ui.runOnIdle { app.preview.seekBy(-50000) }; ui.waitUntil(10000) { app.preview.state.positionMs < 150 }
            ui.runOnIdle { app.preview.toggle(); app.preview.seekTo(23990); app.preview.stop() }
            ui.waitUntil(10000) { !app.preview.state.playing && app.preview.state.positionMs < 150 }
            SystemClock.sleep(200)
            ui.runOnIdle { assertFalse(app.preview.state.playing); assertTrue(app.preview.state.positionMs < 150) }
            ui.onNodeWithTag("preview-stop").performClick(); ui.runOnIdle { assertNull(app.preview.state.key) }
        } finally {
            ui.runOnIdle { app.stopPreview(); app.sheet=null; app.setAppearance(theme) }
            voices.forEach { ModelPackRepository.deleteVoice(app,pack.id,it.id) }; source.delete()
            ui.runOnIdle { previous?.let { app.selectVoice(it.first,it.second) } }
        }
    }
    @Test fun denoiseThenGainSelectionAndSave() {
        ready(); val app=ui.activity; val pack=app.selection!!.first; val previous=app.selection; val theme=app.appearance
        val source=source(); val immutableSource=source.readBytes(); var savedId: String?=null
        try {
            ui.runOnIdle { app.showImportedVoice(pack.id,Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            val dialog=app.recordingDialog!!
            ui.runOnIdle { dialog.changeTrim(2000,22000); dialog.applyTrim(false) }
            ui.waitUntil(60000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED && dialog.enhancedReady }
            val raw=field(dialog,"rawTrim") as File; val denoised=field(dialog,"rawEnhanced") as File
            val rawBytes=raw.readBytes(); val denoisedBytes=denoised.readBytes()
            scroll("denoise-before")
            ui.onNodeWithTag("review-waveform-before",useUnmergedTree=true).performScrollTo()
            val list = ui.onNodeWithTag("sheet-content")
            fun scrollValue() = list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            val scrollBefore = scrollValue()
            val selectedBefore = field(dialog,"useEnhanced")
            ui.onNodeWithTag("review-waveform-before",useUnmergedTree=true).performTouchInput { swipe(center, center + Offset(0f,-450f),500) }
            ui.waitForIdle()
            assertTrue("Vertical drag across a waveform must scroll the review", scrollValue() > scrollBefore)
            assertEquals("Scrolling must not select another version",selectedBefore,field(dialog,"useEnhanced"))
            scroll("denoise-after")
            ui.onNodeWithTag("review-waveform-after",useUnmergedTree=true).performScrollTo()
            val waveTop = ui.onNodeWithTag("review-waveform-after",useUnmergedTree=true).getUnclippedBoundsInRoot().top
            val sheetTop = ui.onNodeWithTag("glass-sheet").getUnclippedBoundsInRoot().top
            capture("card-scroll-before")
            ui.onNodeWithTag("review-waveform-after",useUnmergedTree=true).performTouchInput { swipe(center, center + Offset(0f,400f),500) }
            ui.waitForIdle()
            capture("card-scroll-after")
            assertTrue("Downward drag must move content back",ui.onNodeWithTag("review-waveform-after",useUnmergedTree=true).getUnclippedBoundsInRoot().top > waveTop)
            assertEquals("Content scrolling must not move the sheet",sheetTop,ui.onNodeWithTag("glass-sheet").getUnclippedBoundsInRoot().top)
            assertEquals(selectedBefore,field(dialog,"useEnhanced"))
            scroll("denoise-before")
            val cardBefore = scrollValue()
            ui.onNodeWithTag("denoise-before").performTouchInput { swipe(Offset(18f,100f),Offset(18f,-350f),500) }
            ui.waitForIdle()
            assertTrue("Card-background drags must scroll, not select",scrollValue() > cardBefore)
            assertEquals(selectedBefore,field(dialog,"useEnhanced"))
            for (mode in listOf("dark","light","amoled")) {
                ui.runOnIdle { app.setAppearance(mode) }; scroll("edit-volume"); capture("$mode-review-gain")
                scroll("denoise-before"); capture("$mode-review-before")
                scroll("denoise-after"); capture("$mode-review-after")
            }
            scroll("denoise-before"); ui.onNodeWithTag("denoise-before").performTouchInput { click(Offset(72f,72f)) }
            ui.runOnIdle { assertFalse(field(dialog,"useEnhanced") as Boolean) }
            scroll("preview-after"); ui.onNodeWithTag("preview-after").performTouchInput { click() }
            val player=field(dialog,"preview") as SamplePreview
            ui.waitUntil(10000) { player.state.playing }
            ui.runOnIdle { assertFalse(field(dialog,"useEnhanced") as Boolean) }
            ui.onNodeWithTag("after-stop").performTouchInput { click() }
            ui.waitUntil(10000) { !player.state.playing && player.state.positionMs < 150 }
            ui.runOnIdle { assertFalse(field(dialog,"useEnhanced") as Boolean); dialog.changeVolume(2f); dialog.renderReview() }
            ui.waitUntil(30000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            val processed=field(dialog,"trimmed") as File; val amplified=processed.readBytes()
            assertArrayEquals(rawBytes,raw.readBytes()); assertArrayEquals(denoisedBytes,denoised.readBytes())
            // Hold real work in its queue so even a fast device exposes every busy frame.
            val release = java.util.concurrent.CountDownLatch(1)
            (field(dialog,"worker") as java.util.concurrent.ExecutorService).execute { release.await(15,java.util.concurrent.TimeUnit.SECONDS) }
            try {
                scroll("edit-volume")
                ui.runOnIdle { dialog.changeVolume(1f); dialog.renderReview(); assertEquals(VoiceRecordingDialog.Phase.REVIEW_PROCESSING,dialog.phase) }
                ui.onNodeWithTag("edit-volume").assertExists().assertIsNotEnabled()
                capture("gain-rebuild-visible")
                scroll("denoise-before")
                ui.onNodeWithTag("denoise-before").assertExists()
                capture("before-rebuild-visible")
                scroll("denoise-after")
                ui.onNodeWithTag("denoise-after").assertExists()
                ui.onNodeWithTag("record-save").assertExists().assertIsNotEnabled()
                capture("after-rebuild-visible")
            } finally { release.countDown() }
            ui.waitUntil(30000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("record-save").assertIsEnabled()
            ui.runOnIdle { dialog.changeVolume(1f); dialog.renderReview() }; ui.waitUntil(30000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.runOnIdle { dialog.changeVolume(2f); dialog.renderReview() }; ui.waitUntil(30000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            assertArrayEquals(amplified,processed.readBytes())
            scroll("normalize-volume"); ui.onNodeWithTag("normalize-volume").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            ui.waitUntil(30000) { field(dialog,"working")==false && dialog.phase==VoiceRecordingDialog.Phase.STOPPED &&
                (field(dialog,"normalizationTarget")!=null || field(dialog,"gainNotice")!=null) }
            assertNotNull("Normalization result: ${field(dialog,"gainNotice")}",field(dialog,"normalizationTarget")); assertTrue(Loudness.measure(DeepFilterNet3Denoiser.readRecording(processed)).samplePeak<=.981)
            assertEquals("Both derivatives must be ready after normalization",false,field(dialog,"reviewDirty"))
            assertArrayEquals(rawBytes,raw.readBytes()); assertArrayEquals(denoisedBytes,denoised.readBytes())
            val chosen=processed.readBytes()
            scroll("record-name"); val name="Chosen original QA ${System.nanoTime()}"
            ui.onNodeWithTag("record-name").performTextInput(name); ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog==null }
            val updated=ModelPackRepository.find(app,pack.id)!!; val voice=updated.voices.first { it.displayName==name }; savedId=voice.id
            assertFalse(voice.edit!!.enhanced); assertArrayEquals(chosen,File(updated.voicesDir,voice.fileName).readBytes())
            assertArrayEquals(immutableSource,source.readBytes()); assertArrayEquals(immutableSource,ModelPackRepository.originalSource(updated,voice).readBytes())
            ui.runOnIdle { app.editVoice(updated,voice) }; ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            assertEquals(voice.edit!!.gainDb,PcmGain.db(app.recordingDialog!!.volume),.01f)
            capture("reopened-persisted-selection")
            ui.runOnIdle { app.recordingDialog!!.applyTrim(false) }
            ui.waitUntil(60000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.STOPPED }
            assertArrayEquals(chosen,(field(app.recordingDialog!!,"trimmed") as File).readBytes())
            ui.onNodeWithTag("record-save").performClick(); ui.waitUntil(30000) { app.recordingDialog==null }
            val reopened=ModelPackRepository.find(app,pack.id)!!.voices.first { it.id==voice.id }
            assertArrayEquals(chosen,File(updated.voicesDir,reopened.fileName).readBytes())
            ui.runOnIdle { app.editVoice(updated,reopened) }; ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.applyTrim(false) }; ui.waitUntil(60000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.runOnIdle { app.recordingDialog!!.let { it.changeVolume(it.volume); it.renderReview() } }
            ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("record-save").performClick(); ui.waitUntil(30000) { app.recordingDialog==null }
            assertNull(ModelPackRepository.find(app,pack.id)!!.voices.first { it.id==voice.id }.edit!!.targetLufs)
        } finally {
            ui.runOnIdle { app.recordingDialog?.close(); app.stopPreview(); app.setAppearance(theme) }
            savedId?.let { ModelPackRepository.deleteVoice(app,pack.id,it) }; source.delete()
            ui.runOnIdle { previous?.let { app.selectVoice(it.first,it.second) } }
        }
    }
}
