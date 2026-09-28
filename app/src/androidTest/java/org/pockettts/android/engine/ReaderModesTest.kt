package org.pockettts.android.engine

import android.graphics.Bitmap
import android.os.Bundle
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderModesTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle(); android.os.SystemClock.sleep(350)
        val folder = File(ui.activity.getExternalFilesDir(null), "reader-modes-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
            File(folder, "$name.png").outputStream().use { output -> it.compress(Bitmap.CompressFormat.PNG, 100, output) }; it.recycle()
        }
    }
    @Test fun readOnlyDefaultAndNativeEditingClipboardAndRecreation() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity; val original = app.editor.text.toString()
        try {
            ui.runOnIdle { app.switchTab(0); app.setEditing(false); app.editor.setText("Read this text.") }
            ui.onNodeWithTag("reader-text").performTouchInput { click(center); longClick(center) }
            ui.runOnIdle {
                assertFalse(app.editing); assertFalse(app.editor.hasFocus()); assertFalse(app.editor.isCursorVisible)
                assertNull(app.editor.keyListener)
                assertNull(app.editor.onCreateInputConnection(EditorInfo()))
                app.editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
                assertFalse(app.editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "overwrite") }))
                assertEquals("Read this text.", app.editor.text.toString())
                assertFalse(androidx.core.view.ViewCompat.getRootWindowInsets(app.editor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            }
            capture("read-mode")
            ui.onNodeWithTag("reader-edit").performClick()
            ui.runOnIdle {
                assertTrue(app.editing); assertTrue(app.editor.isCursorVisible)
                val connection = app.editor.onCreateInputConnection(EditorInfo())!!
                app.editor.setSelection(app.editor.length())
                connection.commitText(" Added", 1)
                connection.deleteSurroundingText(1, 0)
                assertEquals("Read this text. Adde", app.editor.text.toString())
                app.editor.setSelection(0, 4)
                assertTrue(app.editor.onTextContextMenuItem(android.R.id.copy))
                assertTrue(app.editor.onTextContextMenuItem(android.R.id.cut))
                app.editor.setSelection(0)
                assertTrue(app.editor.onTextContextMenuItem(android.R.id.paste))
                assertEquals("Read this text. Adde", app.editor.text.toString())
            }
            capture("edit-mode")
            ui.onNodeWithTag("reader-edit").performClick()
            ui.runOnIdle { assertFalse(app.editing); assertEquals("Read this text. Adde", app.editor.text.toString()); assertNull(app.editor.keyListener) }
            ui.activityRule.scenario.recreate()
            ui.waitUntil(15000) { ui.activity.documentReady }
            ui.runOnIdle { assertFalse(ui.activity.editing); assertEquals("Read this text. Adde", ui.activity.editor.text.toString()) }
        } finally { ui.runOnIdle { ui.activity.setEditing(false); ui.activity.editor.setText(original) } }
    }
    @Test fun followActualLayoutAfterManualScrollPauseAndEdits() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity; val original = app.editor.text.toString()
        val listener = MainActivity::class.java.getDeclaredField("readerListener").apply { isAccessible = true }.get(app) as ReaderPlaybackService.Listener
        val serviceField = MainActivity::class.java.getDeclaredField("readerService").apply { isAccessible = true }
        ui.waitUntil(10000) { serviceField.get(app) != null }
        val service = serviceField.get(app) as ReaderPlaybackService
        val text = (1..240).joinToString("\n") { "Paragraph $it: " + "Wrapped words with varying line height. ".repeat(it % 4 + 1) }
        fun snapshot(n: Int, state: ReaderPlaybackService.State = ReaderPlaybackService.State.PLAYING): ReaderPlaybackService.Snapshot {
            val a = text.indexOf("Paragraph $n:")
            return ReaderPlaybackService.Snapshot(state, document = text, rangeStart = a, rangeEnd = a + 30)
        }
        fun visible(n: Int): Boolean {
            val editor = app.editor; val layout = editor.layout ?: return false
            val line = layout.getLineForOffset(text.indexOf("Paragraph $n:"))
            val top = layout.getLineTop(line)
            val rect = android.graphics.Rect()
            if (!editor.getLocalVisibleRect(rect)) return false
            return top >= editor.scrollY && top < editor.scrollY + editor.height - editor.totalPaddingTop - editor.totalPaddingBottom &&
                top + editor.totalPaddingTop >= rect.top && top + editor.totalPaddingTop < rect.bottom
        }
        ui.runOnIdle { service.removeListener(listener); app.setEditing(false); app.switchTab(0); app.editor.setText(text) }
        try {
            ui.runOnIdle {
                app.editor.text.setSpan(RelativeSizeSpan(1.7f), text.indexOf("Paragraph 90:"), text.indexOf("Paragraph 130:"), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                listener.onReaderStateChanged(snapshot(100))
            }
            ui.waitUntil(7000) { visible(100) }
            capture("following-wrapped-text")
            val beforeDrag = app.editor.scrollY
            ui.onNodeWithTag("reader-text").performTouchInput { swipeDown() }
            ui.runOnIdle { assertNotEquals("Read Mode must remain manually scrollable", beforeDrag, app.editor.scrollY) }
            // Explicit held gesture proves a chunk transition cannot fight ongoing touch.
            ui.runOnIdle { app.readingHighlight.gestureStarted(); app.editor.scrollTo(0, 0); listener.onReaderStateChanged(snapshot(180)) }
            ui.waitForIdle(); android.os.SystemClock.sleep(400)
            ui.runOnIdle { assertEquals(0, app.editor.scrollY); app.readingHighlight.gestureEnded() }
            ui.waitUntil(7000) { visible(180) }
            if (ui.onNodeWithTag("reader-scroll").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) {
                ui.runOnIdle { app.readingHighlight.gestureStarted() }
                ui.onNodeWithTag("reader-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 3000f) }
                ui.runOnIdle { app.readingHighlight.gestureEnded(); listener.onReaderStateChanged(snapshot(179)) }
                ui.waitUntil(7000) { visible(179) }
            }
            var pausedScroll = 0
            ui.runOnIdle {
                listener.onReaderStateChanged(snapshot(180, ReaderPlaybackService.State.PAUSED))
                app.editor.scrollTo(0, 200); pausedScroll = app.editor.scrollY
            }
            ui.waitForIdle(); android.os.SystemClock.sleep(400)
            ui.runOnIdle { assertEquals(pausedScroll, app.editor.scrollY); listener.onReaderStateChanged(snapshot(180)) }
            ui.waitUntil(7000) { visible(180) }
            for (n in listOf(4, 120, 235)) {
                ui.runOnIdle { listener.onReaderStateChanged(snapshot(n)) }
                ui.waitUntil(7000) { visible(n) }
                ui.runOnIdle {
                    val span = app.editor.text.getSpans(0, app.editor.length(), BackgroundColorSpan::class.java).single()
                    assertEquals(text.indexOf("Paragraph $n:"), app.editor.text.getSpanStart(span))
                }
            }
            ui.runOnIdle { app.setEditing(true, false); app.editor.append(" changed") }
            ui.runOnIdle { assertTrue(app.editor.text.getSpans(0, app.editor.length(), BackgroundColorSpan::class.java).isEmpty()) }
            ui.runOnIdle { listener.onReaderStateChanged(snapshot(4)); app.setEditing(false) }
            ui.runOnIdle { assertTrue(app.editor.text.getSpans(0, app.editor.length(), BackgroundColorSpan::class.java).isEmpty()) }
            ui.runOnIdle { listener.onReaderStateChanged(ReaderPlaybackService.Snapshot(ReaderPlaybackService.State.STOPPED)) }
        } finally {
            ui.runOnIdle { listener.onReaderStateChanged(ReaderPlaybackService.Snapshot(ReaderPlaybackService.State.IDLE)); app.setEditing(false); app.editor.setText(original); service.addListener(listener) }
        }
    }
    @Test fun newAboutEntryFeaturesNoticesAndNoRepositoryLinks() {
        ui.waitUntil(15000) { ui.activity.documentReady }
        val app = ui.activity; val mode = app.appearance
        try {
            ui.runOnIdle { app.switchTab(2) }
            ui.onNodeWithText(app.getString(R.string.app_description)).assertDoesNotExist()
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("about-entry"))
            ui.onNodeWithTag("about-entry").performClick()
            ui.onNodeWithText(app.getString(R.string.about_intro)).assertExists()
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }
                capture("about-$theme")
            }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("about-notices"))
            ui.onNodeWithTag("about-notices").performClick()
            ui.onNodeWithTag("sheet-content").performScrollToIndex(5)
            ui.waitUntil(5000) { ui.onAllNodes(hasText("DeepFilterNet3 model: MIT", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            val screen = ui.onAllNodes(isRoot()).printToString()
            assertFalse(screen.contains("github", ignoreCase = true)); assertFalse(screen.contains("repository", ignoreCase = true))
            ui.runOnIdle { app.sheet = null }
        } finally { ui.runOnIdle { app.sheet = null; app.setAppearance(mode); app.switchTab(0) } }
    }
}
