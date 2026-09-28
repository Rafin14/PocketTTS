package org.pockettts.android.engine

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ReaderImeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun playWithFocusedEditorAndKeyboardTransitions() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity
        assumeTrue(app.selection != null)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val keyboard = shell("settings get secure show_ime_with_hard_keyboard")
        var original = ""
        shell("settings put secure show_ime_with_hard_keyboard 1")
        ui.runOnIdle { original = app.editor.text.toString(); app.editor.setText(" "); app.setEditing(true, false); app.switchTab(0) }
        ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(app.editor)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        try {
            repeat(3) {
                ui.onNodeWithTag("document").performTouchInput { click(center) }
                ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(app.editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                ui.runOnIdle { assertTrue(app.editor.hasFocus()); app.editor.append(" ") }
                // Blank input deliberately exercises Play/IME/validation without invoking
                // ARM64 inference under an x86 emulator's unsupported native translator.
                ui.onNodeWithText(app.getString(R.string.reader_play)).assertIsDisplayed().performClick()
                ui.waitForIdle()
                ui.runOnIdle {
                    assertEquals(ReaderPlaybackService.State.ERROR, app.snapshot.state)
                    assertTrue(app.editor.hasFocus())
                    assertTrue(ViewCompat.getRootWindowInsets(app.editor)!!.isVisible(WindowInsetsCompat.Type.ime()))
                    app.editor.append(" ")
                    app.pauseReader(); app.stopReader(); app.stopReader()
                }
                if (ViewCompat.getRootWindowInsets(app.editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                    automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(app.editor)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
                ui.waitForIdle()
            }
        } finally {
            shell("settings put secure show_ime_with_hard_keyboard $keyboard")
            ui.runOnIdle { app.stopReader(); app.editor.setText(original) }
        }
    }
}
