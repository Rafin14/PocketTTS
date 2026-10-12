package org.pockettts.android.engine

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real repository fixtures; verifies compact paint without sacrificing hit regions. */
class SpacingAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(1000); ui.waitForIdle(); android.os.SystemClock.sleep(600); ui.waitForIdle()
        val app = ui.activity
        val folder = File(app.getExternalFilesDir(null), "spacing-qa").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(folder, "$name.json").writeText(org.json.JSONObject().put("theme", app.appearance)
            .put("width", bitmap.width).put("height", bitmap.height).put("api", android.os.Build.VERSION.SDK_INT)
            .put("densityDpi", app.resources.displayMetrics.densityDpi).put("fontScale", app.resources.configuration.fontScale)
            .put("package", app.packageName).put("variant", "debug QA").toString(2))
        bitmap.recycle()
    }
    private fun gap(first: String, second: String, minimum: Float) {
        val a = ui.onNodeWithTag(first).fetchSemanticsNode().touchBoundsInRoot
        val b = ui.onNodeWithTag(second).fetchSemanticsNode().touchBoundsInRoot
        val distance = if (kotlin.math.abs(a.top - b.top) < 2) b.left - a.right else b.top - a.bottom
        assertTrue("$first → $second hit-region gap: $distance", distance / ui.activity.resources.displayMetrics.density >= minimum - .5f)
    }
    @Test fun compactActionsHaveElegantGapsAndAccessibleTargets() {
        ui.waitUntil(60000) { ui.activity.documentReady }
        val app = ui.activity; check(app.packageName.endsWith(".qa"))
        val previous = app.selection!!; val mode = app.appearance; val text = app.editor.text.toString(); val editing = app.editing
        val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val source = File(app.cacheDir, "spacing-reference.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(24000 * 6) { kotlin.math.sin(it * .13).toFloat() * .1f }) }
        val voice = ModelPackRepository.importRecording(app, pack.id, "Spacing reference", source).voices.last()
        try {
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.selectVoice(pack, voice); app.switchTab(1); app.detail = pack to voice }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("details-export"))
                for (tag in listOf("details-preview", "details-use", "details-delete", "details-rename", "details-edit", "details-export")) {
                    val hit = ui.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
                    val density = app.resources.displayMetrics.density
                    assertTrue("48 dp hit region: $tag = $hit", hit.height / density >= 47.9f && hit.width / density >= 47.9f)
                    if (app.resources.configuration.fontScale <= 1.05f) {
                        val height = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / app.resources.displayMetrics.density
                        assertTrue("Compact painted action: $tag = $height", height <= 44.5f)
                    }
                }
                gap("details-preview", "details-use", 10f)
                gap("details-use", "details-delete", 10f)
                gap("details-delete", "details-rename", 12f)
                gap("details-rename", "details-edit", 10f)
                gap("details-edit", "details-export", 12f)
                capture("$theme-details-spacing")
                ui.onNodeWithTag("sheet-close").performClick()
                ui.runOnIdle { app.switchTab(0); app.setEditing(true); app.editor.setText("A disposable document for confirmation testing."); app.clearDocument() }
                ui.runOnIdle { assertNotNull("Clear confirmation must actually be open", app.confirmation) }
                ui.onNodeWithText(app.getString(R.string.reader_clear_confirm)).assertExists()
                capture("$theme-clear-text-confirmation")
                ui.runOnIdle { app.confirmation = null; app.setEditing(false); app.beginRename(pack, voice) }
                ui.onNodeWithTag("rename-name").performTextReplacement(pack.voices.first { !it.userCreated }.displayName)
                ui.onNodeWithTag("rename-save").performClick()
                ui.waitUntil(15000) { !app.busy && app.renameError != null }
                capture("$theme-rename-validation")
                ui.runOnIdle { app.renameTarget = null; app.renameError = null; app.showAbout() }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("about-notices"))
                ui.onNodeWithTag("about-notices").performClick()
                capture("$theme-expanded-license-notices")
                ui.onNodeWithTag("sheet-close").performClick()
            }
        } finally {
            ui.runOnIdle { app.detail = null; app.sheet = null; app.confirmation = null; app.renameTarget = null; app.renameError = null
                app.setAppearance(mode); app.editor.setText(text); app.setEditing(editing); app.selectVoice(previous.first, previous.second) }
            ModelPackRepository.deleteVoice(app, pack.id, voice.id); source.delete()
        }
    }
}
