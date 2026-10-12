package org.pockettts.android.engine

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class InteractionGesturesTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        ui.waitUntil(60000) { ui.activity.documentReady }
        check(ui.activity.packageName.endsWith(".qa"))
        ui.runOnIdle { ui.activity.switchTab(0) }
    }
    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(1000); ui.waitForIdle(); SystemClock.sleep(600); ui.waitForIdle()
        val directory = File(ui.activity.getExternalFilesDir(null), "interaction-evidence").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun swipesEdgesRapidTapsAndOutsideCancellation() {
        ready(); val app = ui.activity
        val original = app.editor.text.toString()
        val editing = app.editing
        val speed = app.speed
        try {
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeLeft() }
            ui.runOnIdle { assertEquals(1, app.tab) }
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeLeft() }
            ui.runOnIdle { assertEquals(2, app.tab) }
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeLeft() }
            ui.runOnIdle { assertEquals(2, app.tab) }
            capture("settings-swipe")
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("about-entry"))
            val scroll = ui.onNodeWithTag("settings-list").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(scroll > 0f)
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeRight() }
            ui.runOnIdle { assertEquals(1, app.tab) }
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeLeft() }
            assertEquals(scroll, ui.onNodeWithTag("settings-list").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), .5f)
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeRight() }
            ui.onNodeWithTag("navigation-panel").performTouchInput {
                down(Offset(width * .15f, height * .5f)); advanceEventTime(700)
                moveTo(Offset(width * .85f, height * .5f), delayMillis=150); up()
            }
            ui.runOnIdle { assertEquals(2, app.tab) }
            ui.onNodeWithTag("nav-0").performClick()
            ui.onNodeWithTag("navigation-panel").performTouchInput {
                down(Offset(width * .15f, height * .5f)); advanceEventTime(700)
                moveTo(Offset(width * .85f, height * .5f), delayMillis=150)
                moveTo(Offset(width * .85f, -80f), delayMillis=150); up()
            }
            ui.runOnIdle { assertEquals(0, app.tab) }
            ui.onNodeWithTag("navigation-panel").performTouchInput {
                down(Offset(width * .15f, height * .5f)); advanceEventTime(700)
                moveTo(Offset(width * .5f, height * .5f), delayMillis=150); cancel()
            }
            ui.runOnIdle { assertEquals(0, app.tab) }
            repeat(3) { for (index in listOf(2,1,0)) ui.onNodeWithTag("nav-$index").performClick() }
            ui.runOnIdle { assertEquals(original, app.editor.text.toString()); assertEquals(editing, app.editing) }
            capture("reader-after-cancel")
            ui.onNodeWithTag("reader-speed").performClick()
            ui.onNodeWithTag("playback-speed").performTouchInput { swipeLeft() }
            ui.runOnIdle { assertEquals(0, app.tab) }
            capture("speed-sheet")
        } finally { ui.runOnIdle { app.sheet = null; app.selectSpeed(speed); app.switchTab(0) } }
    }
    @Test fun dragPreviewDoesNotCommitAndAllThemesRespectReducedMotion() {
        ready(); val app = ui.activity; val appearance = app.appearance
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val animator = shell("settings get global animator_duration_scale")
        try {
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.switchTab(0) }
                ui.onNodeWithTag("navigation-panel").performTouchInput {
                    down(Offset(width * .15f, height * .5f)); advanceEventTime(700)
                    moveTo(Offset(width * .5f, height * .5f), delayMillis=150)
                }
                ui.runOnIdle { assertEquals(0, app.tab) }
                ui.onNodeWithTag("nav-0").assertIsSelected()
                ui.onNodeWithTag("nav-1").assertIsNotSelected()
                ui.onNodeWithTag("navigation-panel").assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    app.getString(R.string.nav_drag_preview, app.getString(R.string.tab_voices))))
                capture("$theme-drag-preview")
                ui.onNodeWithTag("navigation-panel").performTouchInput { cancel() }
                ui.runOnIdle { assertEquals(0, app.tab) }
            }
            shell("settings put global animator_duration_scale 0")
            ui.onNodeWithTag("tab-swipe-header").performTouchInput { swipeLeft() }
            ui.runOnIdle { assertEquals(1, app.tab) }
            capture("reduced-motion-voices")
        } finally {
            ui.runOnIdle { app.setAppearance(appearance); app.switchTab(0) }
            shell(if(animator == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $animator")
        }
    }
    @Test fun nativeFeedbackQuantizationToggleAndPlaybackSuccess() {
        ready(); val app = ui.activity
        val previousSpeed = app.speed; val previousGain = app.readerGainDb; val enabled = app.hapticsEnabled
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val systemHaptics = shell("settings get system haptic_feedback_enabled")
        shell("settings put system haptic_feedback_enabled 1")
        val requests = mutableListOf<Int>()
        val probe = object : View(app) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean { requests += feedbackConstant; return true }
        }
        val root = app.findViewById<ViewGroup>(android.R.id.content)
        val source = File(app.cacheDir, "haptic-preview-${System.nanoTime()}.wav")
        val player = SamplePreview(app)
        PcmWav.Writer(source).use { output -> repeat(6) { output.write(FloatArray(PcmWav.RATE) { kotlin.math.sin(it * .06).toFloat() * .05f }) } }
        try {
            ui.runOnIdle { root.addView(probe, ViewGroup.LayoutParams(1,1)); app.haptics.attach(probe); app.setHaptics(true); app.selectSpeed(1f) }
            SystemClock.sleep(120)
            ui.runOnIdle { requests.clear() }
            ui.onNodeWithTag("nav-1").performClick()
            ui.runOnIdle { assertEquals(1, app.tab); assertEquals(1, requests.size) }
            ui.onNodeWithTag("nav-1").performClick()
            ui.runOnIdle { assertEquals(1, requests.size); app.setHaptics(false) }
            ui.onNodeWithTag("nav-2").performClick()
            ui.runOnIdle { assertEquals(2, app.tab); assertEquals(1, requests.size); app.setHaptics(true); app.switchTab(0) }
            SystemClock.sleep(120)
            ui.runOnIdle { requests.clear(); app.selectSpeed(1.05f); assertEquals(1, requests.size); repeat(20) { app.selectSpeed(1.05f) }; assertEquals(1, requests.size) }
            SystemClock.sleep(120)
            ui.runOnIdle { app.setHaptics(false); app.selectSpeed(1.1f); assertEquals(1, requests.size); app.setHaptics(true) }
            shell("settings put system haptic_feedback_enabled 0")
            ui.runOnIdle { app.selectSpeed(1.15f); assertEquals(1, requests.size) }
            shell("settings put system haptic_feedback_enabled 1")
            SystemClock.sleep(120)
            ui.runOnIdle { requests.clear(); player.play(source, retain=true) { fail("Synthetic preview failed") } }
            ui.waitUntil(10000) { player.state.playing }
            ui.runOnIdle { assertEquals(1, requests.size) }
            SystemClock.sleep(120)
            ui.runOnIdle { player.toggle(); assertFalse(player.state.playing); assertEquals(2, requests.size) }
            SystemClock.sleep(120)
            ui.runOnIdle { player.stopFromControl(); assertEquals(3, requests.size); player.stopFromControl(); assertEquals(3, requests.size) }
            SystemClock.sleep(120)
            ui.runOnIdle {
                requests.clear(); app.haptics.tick(); app.haptics.confirm()
                assertEquals("A hover tick must not swallow a successful commit", 2, requests.size)
                app.haptics.confirm(); assertEquals(2, requests.size)
            }
            ui.runOnIdle { app.switchTab(2) }
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("haptic-toggle"))
            capture("haptics-settings")
            ui.onNodeWithTag("haptic-toggle").performClick()
            ui.runOnIdle { assertFalse(app.hapticsEnabled); assertFalse(app.getSharedPreferences("pockettts_reader",0).getBoolean("haptic_feedback",true)) }
        } finally {
            ui.runOnIdle { player.close(); app.haptics.detach(probe); root.removeView(probe); app.setHaptics(enabled); app.selectSpeed(previousSpeed); app.selectReaderGain(previousGain); app.switchTab(0) }
            source.delete()
            shell(if(systemHaptics == "null") "settings delete system haptic_feedback_enabled" else "settings put system haptic_feedback_enabled $systemHaptics")
        }
    }
}
