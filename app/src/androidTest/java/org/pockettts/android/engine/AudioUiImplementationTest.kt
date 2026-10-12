package org.pockettts.android.engine

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.*

class AudioUiImplementationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun ready()=ui.waitUntil(60000) { ui.activity.documentReady }
    private fun field(target:Any,name:String):Any? = try {
        target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    } catch (_:NoSuchFieldException) {
        (target.javaClass.getDeclaredField("${name}\$delegate").apply { isAccessible=true }.get(target) as androidx.compose.runtime.State<*>).value
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        SystemClock.sleep(500) // UiAutomation captures real display frames, not Compose's virtual clock.
        ui.waitForIdle()
        val dir=File(ui.activity.getExternalFilesDir(null),"implementation-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(dir,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
    }
    private fun fixture(seconds:Int=4)=File(ui.activity.cacheDir,"ui-gain-${System.nanoTime()}.wav").also { file ->
        PcmWav.Writer(file).use { it.write(FloatArray(24000*seconds) { sin(it*.2).toFloat()*.1f }) }
    }

    @Test fun sharedGainNormalizationAndAbsoluteReopeningAcrossThemes() {
        ready(); val app=ui.activity; val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        val previous=app.selection; val mode=app.appearance; val source=fixture(); val label="Gain QA ${System.nanoTime()}"
        var id:String?=null
        try {
            ui.runOnIdle { app.switchTab(1); app.showImportedVoice(pack.id,Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            var dialog=app.recordingDialog!!
            ui.runOnIdle { dialog.applyTrim(false) }
            ui.waitUntil(120000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            for(theme in listOf("light","dark","amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("edit-volume"))
                ui.onNodeWithTag("edit-volume").assertIsDisplayed(); capture("$theme-new-trim-gain")
            }
            for(gain in listOf(.5f,1f,2f)) {
                ui.runOnIdle { dialog.changeVolume(gain); dialog.renderReview() }
                val preview=field(dialog,"preview") as SamplePreview
                ui.waitUntil(30000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
                val pcm=DeepFilterNet3Denoiser.readRecording(field(dialog,"trimmed") as File)
                assertEquals(.1f*gain,pcm.maxOrNull()!!,.001f)
            }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("normalize-volume"))
            ui.onNodeWithTag("normalize-volume").performClick()
            ui.waitUntil(30000) { field(dialog,"working")==false }
            assertNotNull(field(dialog,"normalizationTarget")); capture("amoled-normalization-result")
            // Manual adjustment clears normalization, preserving a simple absolute recipe.
            ui.runOnIdle { dialog.changeVolume(2f); dialog.renderReview() }
            ui.waitUntil(120000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(app.getString(R.string.trim_use_before)))
            ui.onNodeWithText(app.getString(R.string.trim_use_before)).performClick()
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-name"))
            ui.onNodeWithTag("record-name").performTextInput(label)
            ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog==null }
            var current=ModelPackRepository.find(app,pack.id)!!.voices.first { it.displayName==label }; id=current.id
            val saved=File(pack.voicesDir,current.fileName).readBytes()
            assertEquals(PcmGain.db(2f),current.edit!!.gainDb,.001f)
            repeat(2) {
                ui.runOnIdle { app.editVoice(pack,current) }
                ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
                dialog=app.recordingDialog!!; assertEquals(2f,dialog.volume,.001f)
                ui.runOnIdle { dialog.applyTrim(false) }; ui.waitUntil(120000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("edit-volume"))
                capture("amoled-reopened-gain-$it")
                ui.runOnIdle { dialog.applyTrim(false) }
                ui.waitUntil(120000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
                ui.onNodeWithTag("record-save").performClick(); ui.waitUntil(30000) { app.recordingDialog==null }
                current=ModelPackRepository.find(app,pack.id)!!.voices.first { it.id==id }
                assertArrayEquals(saved,File(pack.voicesDir,current.fileName).readBytes())
            }
            assertArrayEquals(source.readBytes(),ModelPackRepository.originalSource(pack,current).readBytes())
        } finally {
            ui.runOnIdle { app.recordingDialog?.close(); app.setAppearance(mode) }
            id?.let { ModelPackRepository.deleteVoice(app,pack.id,it) }; source.delete()
            ui.runOnIdle { previous?.let { app.selectVoice(it.first,it.second) } }
        }
    }

    @Test fun actualReaderGainPreservesPositionPauseSpeedAndDryAudio() {
        ready(); ui.waitUntil(10000) { field(ui.activity,"readerService")!=null }
        val app=ui.activity; val service=field(app,"readerService") as ReaderPlaybackService
        val selected=app.selection!!; val key=ModelPackRepository.voiceName(selected.first,selected.second)
        val priorGain=ReaderGain.get(app,key); val priorSpeed=app.speed; val theme=app.appearance
        val dir=File(app.cacheDir,"reader-implementation-${System.nanoTime()}").apply { mkdirs() }
        val dry=File(dir,"0.wav"); PcmWav.Writer(dry).use { it.write(FloatArray(240000) { sin(it*.2).toFloat()*.1f }) }
        val bytes=dry.readBytes()
        val type=Class.forName("org.pockettts.android.engine.ReaderPlaybackService\$Session")
        val session=type.declaredConstructors.first().apply { isAccessible=true }.newInstance(dir,"Gain fixture",1f,"Synthetic playback")
        @Suppress("UNCHECKED_CAST") (field(session,"files") as MutableList<File>).add(dry)
        (field(session,"timeline") as AudioTimeline).append(10000)
        type.getDeclaredField("voiceKey").apply { isAccessible=true }.set(session,key)
        type.getDeclaredField("complete").apply { isAccessible=true }.setBoolean(session,true)
        type.getDeclaredField("total").apply { isAccessible=true }.setInt(session,1)
        try {
            ui.runOnIdle {
                service.stop(); ReaderPlaybackService::class.java.getDeclaredField("session").apply { isAccessible=true }.set(service,session)
                app.selectReaderGain(0f); service.resume()
            }
            ui.waitUntil(10000) { app.snapshot.state==ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { service.seekTo(2000); app.selectReaderGain(PcmGain.MAX_DB) }
            ui.waitUntil(10000) { app.snapshot.state==ReaderPlaybackService.State.PLAYING && field(service,"prepared")==true }
            val gained=field(service,"gainedFile") as File
            assertEquals(.2f,DeepFilterNet3Denoiser.readRecording(gained).maxOrNull()!!,.001f)
            assertArrayEquals(bytes,dry.readBytes())
            assertTrue(app.snapshot.positionMs>=1800)
            ui.runOnIdle { service.pause(); app.selectReaderGain(PcmGain.MIN_DB) }
            ui.waitUntil(10000) { field(service,"prepared")==true }
            ui.runOnIdle { assertEquals(ReaderPlaybackService.State.PAUSED,app.snapshot.state); assertFalse((field(service,"player") as MediaPlayer).isPlaying) }
            assertEquals(.05f,DeepFilterNet3Denoiser.readRecording(field(service,"gainedFile") as File).maxOrNull()!!,.001f)
            ui.runOnIdle { app.selectSpeed(1.5f); app.selectSpeed(1f); service.resume() }
            ui.waitUntil(10000) { app.snapshot.state==ReaderPlaybackService.State.PLAYING }
            ui.runOnIdle { assertEquals(1f,(field(service,"player") as MediaPlayer).playbackParams.speed,.001f); assertEquals(PcmGain.MIN_DB,app.readerGainDb,.001f) }
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            try {
                SystemClock.sleep(300)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertTrue("Real synthetic Reader audio survives backgrounding", (field(service,"player") as MediaPlayer).isPlaying)
                    val media = field(service,"mediaSession") as android.media.session.MediaSession
                    assertEquals(android.media.session.PlaybackState.STATE_PLAYING, media.controller.playbackState!!.state)
                }
            } finally { ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
            for(mode in listOf("light","dark","amoled")) {
                ui.runOnIdle { app.setAppearance(mode); app.sheet="gain" }
                ui.onNodeWithText(app.getString(R.string.reader_gain)).assertIsDisplayed()
                capture("$mode-reader-gain")
                ui.runOnIdle { app.sheet=null }; capture("$mode-reader-controls")
            }
            ui.runOnIdle { app.sheet="speed" }; capture("amoled-speed-reset-retained"); ui.runOnIdle { app.sheet=null }
            repeat(5) { ui.runOnIdle { app.selectReaderGain(if(it%2==0) 3f else -3f); service.pause(); service.resume() } }
            ui.runOnIdle { service.stop() }; assertEquals(ReaderPlaybackService.State.STOPPED,app.snapshot.state)
        } finally { ui.runOnIdle { app.sheet=null; service.stop(); app.selectReaderGain(priorGain); app.selectSpeed(priorSpeed); app.setAppearance(theme) }; dry.delete(); dir.listFiles()?.forEach(File::delete); dir.delete() }
    }

    @Test fun normalizedEnhancementPreviewEqualsSavedBytes() {
        ready(); val app=ui.activity; val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        val previous=app.selection; val mode=app.appearance; val source=fixture(); val name="Normalized QA ${System.nanoTime()}"
        var id:String?=null
        try {
            ui.runOnIdle { app.setAppearance("dark"); app.switchTab(1); app.showImportedVoice(pack.id,Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            val dialog=app.recordingDialog!!
            ui.runOnIdle { dialog.applyTrim(false) }; ui.waitUntil(120000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("normalize-volume"))
            ui.onNodeWithTag("normalize-volume").performClick(); ui.waitUntil(30000) { field(dialog,"working")==false }
            val target=field(dialog,"normalizationTarget") as Double
            ui.runOnIdle { dialog.applyTrim(false) }; capture("dark-normalization-processing")
            ui.waitUntil(120000) { dialog.phase==VoiceRecordingDialog.Phase.STOPPED }
            assertTrue(dialog.enhancedReady)
            val enhanced=field(dialog,"enhanced") as File
            val pcm=DeepFilterNet3Denoiser.readRecording(enhanced)
            val measured=Loudness.measure(pcm)
            assertTrue(measured.lufs.isFinite()); assertTrue(measured.samplePeak<=.981)
            println("NORMALIZED_DF target=$target achieved=${measured.lufs} estimatedPeak=${measured.estimatedPeak}")
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-after"))
            ui.onNodeWithTag("preview-after").performClick()
            val preview=field(dialog,"preview") as SamplePreview
            ui.waitUntil(10000) { preview.state.playing }; capture("dark-normalized-enhancement-preview")
            ui.runOnIdle { preview.close() }
            val accepted=enhanced.readBytes()
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("record-name"))
            ui.onNodeWithTag("record-name").performTextInput(name); ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog==null }
            val current=ModelPackRepository.find(app,pack.id)!!.voices.first { it.displayName==name }; id=current.id
            assertEquals(target,current.edit!!.targetLufs!!,0.0)
            assertArrayEquals(accepted,File(pack.voicesDir,current.fileName).readBytes())
            ui.runOnIdle { app.switchTab(1) }
            ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${current.id}"))
            capture("dark-normalized-voice-selected")
            ui.onNodeWithTag("voice-actions-${current.id}").performClick(); capture("dark-edited-voice-menu")
            ui.onNodeWithText(app.getString(R.string.voice_edit_action)).performClick()
            ui.waitUntil(30000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.applyTrim(false) }
            ui.waitUntil(120000) { app.recordingDialog?.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("edit-volume"))
            assertEquals(target,field(app.recordingDialog!!,"normalizationTarget") as Double,0.0)
            capture("dark-reopened-normalized-edit")
        } finally {
            ui.runOnIdle { app.recordingDialog?.close(); app.stopPreview(); app.setAppearance(mode) }
            id?.let { ModelPackRepository.deleteVoice(app,pack.id,it) }; source.delete()
            ui.runOnIdle { previous?.let { app.selectVoice(it.first,it.second) } }
        }
    }

    @Test fun benchmarkActualRefreshScalingWithoutReplacingUserAudio() {
        ready(); val app=ui.activity; val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        val manifest=pack.manifestFile; val bytes=manifest.readBytes()
        val refresh=MainActivity::class.java.getDeclaredMethod("refreshData",String::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        try {
            for(count in listOf(1,100,1000)) {
                val json=JSONObject(bytes.toString(Charsets.UTF_8)); val voices=json.getJSONArray("voices")
                repeat(count-1) { voices.put(JSONObject().put("id","benchmark-$it").put("name","Benchmark $it").put("file","alba.wav")) }
                synchronized(ManifestStorage.lock) { manifest.writeText(json.toString()) }
                val durations=mutableListOf<Double>(); val completions=mutableListOf<Double>()
                repeat(7) { iteration ->
                    val marker="Benchmark $count-$iteration"
                    voices.getJSONObject(0).put("name",marker)
                    synchronized(ManifestStorage.lock) { manifest.writeText(json.toString()) }
                    val request=SystemClock.elapsedRealtimeNanos()
                    ui.runOnIdle { val start=SystemClock.elapsedRealtimeNanos(); refresh.invoke(app,null,false); durations+=(SystemClock.elapsedRealtimeNanos()-start)/1e6 }
                    ui.waitUntil(10000) { app.packs.first { it.id==pack.id }.voices.first().displayName==marker }
                    completions+=(SystemClock.elapsedRealtimeNanos()-request)/1e6
                }
                println("REFRESH_BENCHMARK voices=$count dispatchMedianMs=${durations.sorted()[3]} dispatchMaxMs=${durations.maxOrNull()} completionMedianMs=${completions.sorted()[3]}")
            }
        } finally { synchronized(ManifestStorage.lock) { manifest.writeBytes(bytes) }; ui.runOnIdle { refresh.invoke(app,null,false) } }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion=33)
    @Test fun silentNormalizationAndLongPseudoLocalizedControls() {
        ready(); val app=ui.activity; val theme=app.appearance; val selection=app.selection!!
        val source=File(app.cacheDir,"silent-ui-${System.nanoTime()}.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(72000)) }
        val locales=app.getSystemService(android.app.LocaleManager::class.java)
        val previous=locales.applicationLocales
        try {
            ui.runOnIdle { app.setAppearance("dark"); app.switchTab(1); app.showImportedVoice(selection.first.id,Uri.fromFile(source)) }
            ui.waitUntil(30000) { ui.activity.recordingDialog?.phase==VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { ui.activity.recordingDialog!!.applyTrim(false) }
            ui.waitUntil(120000) { ui.activity.recordingDialog?.phase==VoiceRecordingDialog.Phase.STOPPED }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("normalize-volume"))
            ui.onNodeWithTag("normalize-volume").performClick()
            ui.waitUntil(30000) { field(ui.activity.recordingDialog!!,"working")==false }
            ui.onNodeWithText(app.getString(R.string.audio_normalize_silent)).assertExists()
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(app.getString(R.string.audio_normalize_silent)))
            capture("dark-silent-normalization-error")
            ui.runOnIdle { app.recordingDialog?.close(); app.switchTab(0) }
            locales.applicationLocales=android.os.LocaleList.forLanguageTags("en-XA")
            ui.waitUntil(15000) { ui.activity.resources.configuration.locales[0].toLanguageTag()=="en-XA" && ui.activity.documentReady }
            ui.runOnIdle {
                val current=ui.activity
                current.selectVoice(selection.first,selection.second.copy(displayName="Long voice name for accessible Reader controls and gain adjustments"))
                current.sheet="gain"
            }
            ui.onNodeWithText(ui.activity.getString(R.string.reader_gain)).assertIsDisplayed()
            capture("dark-pseudolocalized-reader-gain-long-name")
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(ui.activity.getString(R.string.audio_gain_reset)))
            ui.onNodeWithText(ui.activity.getString(R.string.audio_gain_reset)).assertIsDisplayed()
            capture("dark-pseudolocalized-reader-gain-actions")
        } finally {
            locales.applicationLocales=previous
            ui.waitUntil(15000) { ui.activity.documentReady }
            ui.runOnIdle { ui.activity.recordingDialog?.close(); ui.activity.sheet=null; ui.activity.setAppearance(theme); ui.activity.selectVoice(selection.first,selection.second) }
            source.delete()
        }
    }
}
