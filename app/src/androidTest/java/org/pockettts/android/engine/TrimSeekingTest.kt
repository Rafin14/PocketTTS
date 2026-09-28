package org.pockettts.android.engine

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TrimSeekingTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    @Test fun dragTapPausedPlayingBoundariesAndCompletion() {
        ui.waitUntil(30000) { ui.activity.documentReady }
        val app = ui.activity
        val source = File(app.cacheDir, "trim-seeking-test.wav")
        PcmWav.Writer(source).use { out -> repeat(20) { out.write(FloatArray(24000) { kotlin.math.sin(it * .1).toFloat() * .1f }) } }
        try {
            ui.runOnIdle { app.showImportedVoice(BundledPocketTts.ID, Uri.fromFile(source)) }
            ui.waitUntil(15000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            val dialog = app.recordingDialog!!
            val preview = field(dialog, "preview") as SamplePreview
            ui.runOnIdle { dialog.changeTrim(4000, 16000) }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("trim-waveform"))
            // Drag before the first Play must prepare/seek without starting playback.
            ui.onNodeWithTag("trim-waveform").performTouchInput {
                swipe(Offset(width * .3f, center.y), Offset(width * .5f, center.y), 500)
            }
            ui.waitUntil(15000) { preview.state.key != null && !preview.state.loading && kotlin.math.abs(preview.state.positionMs - 6000) < 400 }
            ui.runOnIdle {
                assertFalse(preview.state.playing)
                assertEquals(6000f, (field(preview, "player") as MediaPlayer).currentPosition.toFloat(), 400f)
                repeat(5) { preview.toggle(); preview.toggle() }
                assertFalse(preview.state.playing)
                preview.toggle()
            }
            ui.waitUntil(5000) { preview.state.playing }
            ui.onNodeWithTag("trim-waveform").performTouchInput { click(Offset(width * .6f, center.y)) }
            ui.waitUntil(5000) { preview.state.positionMs in 7600..9500 }
            ui.runOnIdle { assertTrue(preview.state.playing); preview.toggle(); dialog.seekTrim(-100) }
            ui.waitUntil(5000) { preview.state.positionMs == 0 }
            ui.runOnIdle { assertFalse(preview.state.playing); dialog.seekTrim(25000) }
            ui.waitUntil(5000) { preview.state.positionMs >= 11900 }
            ui.runOnIdle { assertFalse(preview.state.playing); dialog.seekTrim(15500) }
            ui.waitUntil(5000) { preview.state.positionMs in 11400..11600 }
            ui.runOnIdle { preview.toggle() }
            ui.waitUntil(5000) { !preview.state.playing && preview.state.positionMs == preview.state.durationMs }
            ui.runOnIdle {
                assertNotNull(preview.state.key) // Completion stays at selected end, never full-source zero.
                dialog.changeTrim(7000, 11000)
                assertNull(preview.state.key); assertEquals(11000, dialog.trimPosition)
                dialog.seekTrim(8000)
            }
            ui.waitUntil(10000) { preview.state.durationMs == 4000 && preview.state.positionMs in 900..1100 }
            ui.runOnIdle { assertFalse(preview.state.playing) }
        } finally { ui.runOnIdle { app.recordingDialog?.close() }; source.delete() }
    }
}
