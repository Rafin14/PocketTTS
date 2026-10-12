package org.pockettts.android.engine

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the redesigned interaction structure against real disposable repository data. */
class StudioAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun ready() { ui.waitUntil(60000) { ui.activity.documentReady }; check(ui.activity.packageName.endsWith(".qa")) }
    @Suppress("UNCHECKED_CAST")
    private fun <T> fixtureState(app: MainActivity, name: String, value: T) {
        (MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(app)
            as androidx.compose.runtime.MutableState<T>).value = value
    }
    private fun capture(name: String) {
        ui.waitForIdle(); android.os.SystemClock.sleep(500)
        val folder = File(ui.activity.getExternalFilesDir(null), "studio-acceptance").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun segmentedSpeedPreservesVoiceAndGainAcrossRecreation() {
        ready(); var app = ui.activity
        assertEquals(pocketColors("light", false).background.toArgb(), app.getColor(R.color.studio_canvas_light))
        assertEquals(pocketColors("dark", false).background.toArgb(), app.getColor(R.color.studio_canvas_dark))
        assertEquals(pocketColors("light", false).primary.toArgb(), app.getColor(R.color.studio_accent_light))
        assertEquals(pocketColors("dark", false).primary.toArgb(), app.getColor(R.color.studio_accent_dark))
        val restartPhase = InstrumentationRegistry.getArguments().getString("restart-phase")
        val restartFile = File(app.filesDir, "studio-restart-qa.json")
        if (restartPhase == "verify") {
            val original = org.json.JSONObject(restartFile.readText())
            try {
                assertNotEquals("A fresh application process is required", original.getInt("pid"), android.os.Process.myPid())
                assertEquals("dark", app.appearance); assertEquals(1f, app.speed); assertEquals(3f, app.readerGainDb)
                assertEquals(original.getString("voice"), ModelPackRepository.voiceName(app.selection!!.first, app.selection!!.second))
                capture("settings-after-process-restart")
            } finally {
                ui.runOnIdle { app.setAppearance(original.getString("mode")); app.selectSpeed(original.getDouble("speed").toFloat()); app.selectReaderGain(original.getDouble("gain").toFloat()) }
                restartFile.delete()
            }
            return
        }
        val originalSpeed = app.speed; val originalGain = app.readerGainDb; val mode = app.appearance
        val key = ModelPackRepository.voiceName(app.selection!!.first, app.selection!!.second)
        try {
            ui.onNodeWithTag("nav-0").performClick()
            ui.runOnIdle { app.selectReaderGain(3f); app.sheet = "speed" }
            for (value in listOf(.75f, 1f, 1.25f, 1.5f)) {
                ui.onNodeWithTag("speed-preset-$value").performClick().assertIsSelected()
                ui.runOnIdle { assertEquals(value, app.speed); assertEquals(3f, app.readerGainDb) }
            }
            ui.onNodeWithTag("speed-reset").performClick()
            ui.onNodeWithTag("speed-preset-1.0").assertIsSelected(); capture("speed-segments-reset")
            ui.onNodeWithTag("sheet-close").performClick()
            ui.runOnIdle { app.setAppearance("dark") }
            ui.activityRule.scenario.recreate(); ready(); app = ui.activity
            assertEquals(1f, app.speed); assertEquals(3f, app.readerGainDb)
            assertEquals(key, ModelPackRepository.voiceName(app.selection!!.first, app.selection!!.second))
            assertEquals("dark", app.appearance)
            ui.onNodeWithTag("nav-2").performClick().assertIsSelected(); capture("settings-recreated")
            ui.onNodeWithTag("nav-1").performClick().assertIsSelected(); capture("library-one-item")
        } finally {
            if (restartPhase == "seed") {
                restartFile.writeText(org.json.JSONObject().put("pid", android.os.Process.myPid()).put("voice", key)
                    .put("mode", mode).put("speed", originalSpeed).put("gain", originalGain).toString())
            } else ui.runOnIdle { app.sheet = null; app.selectReaderGain(originalGain); app.selectSpeed(originalSpeed); app.setAppearance(mode) }
        }
    }

    @Test fun readerNamesWrapWithoutEllipsisAndKeepWorkingActions() {
        ready(); val app = ui.activity; val previous = app.selection!!; val pack = previous.first
        val source = File(app.cacheDir, "studio-names.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(24000 * 4) { kotlin.math.sin(it * .11).toFloat() * .1f }) }
        var voice = ModelPackRepository.importRecording(app, pack.id, "Studio names", source).voices.last()
        val mode = app.appearance
        try {
            for ((index, name) in listOf("J", "Đặng Thị Ngọc Hân", "👩🏽‍💻 Priya", "Aleksandra Wiśniewska-Kowalczyk", "W".repeat(80)).withIndex()) {
                voice = ModelPackRepository.renameVoice(app, pack.id, voice.id, name).voices.first { it.id == voice.id }
                ui.runOnIdle { app.selectVoice(pack, voice); app.switchTab(0); app.setEditing(false); app.setAppearance("dark") }
                if (runCatching { ui.onNodeWithTag("reader-voice").assertIsDisplayed() }.isFailure)
                    ui.onNodeWithTag("reader-scroll").performScrollToNode(hasTestTag("reader-voice"))
                val layouts = mutableListOf<TextLayoutResult>()
                ui.onNodeWithTag("reader-selected-name", useUnmergedTree = true).assertTextEquals(name)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Full name has a layout", layouts.isNotEmpty())
                capture("name-grapheme-$index")
                assertFalse("Name must not overflow: $name, size=${layouts.single().size}, paragraph=${layouts.single().multiParagraph.width}x${layouts.single().multiParagraph.height}, widthOverflow=${layouts.single().didOverflowWidth}, heightOverflow=${layouts.single().didOverflowHeight}", layouts.single().hasVisualOverflow)
                assertFalse(layouts.single().isLineEllipsized(layouts.single().lineCount - 1))
                ui.onNodeWithTag("reader-voice").performClick(); ui.onNodeWithTag("sheet-close").performClick()
            }
        } finally {
            ui.runOnIdle { app.sheet = null; app.selectVoice(previous.first, previous.second); app.setAppearance(mode) }
            ModelPackRepository.deleteVoice(app, pack.id, voice.id); source.delete()
        }
    }

    @Test fun emptyLibraryAndInvalidImportHaveRecoverableActions() {
        ready(); val app = ui.activity; val packs = app.packs; val selected = app.selection; val mode = app.appearance
        val invalid = File(app.cacheDir, "studio-invalid.wav").apply { writeText("Not a WAV fixture") }
        try {
            // Visual fixture only: no voice/model is removed from private storage.
            ui.runOnIdle { fixtureState(app, "packs", emptyList<ModelPack>()); fixtureState(app, "selection", null); app.setAppearance("light"); app.switchTab(1) }
            capture("empty-library-fixture")
            ui.runOnIdle { app.sheet = "voice" }; capture("empty-picker-fixture")
            ui.onNodeWithTag("sheet-close").performClick()
            ui.runOnIdle { fixtureState(app, "packs", packs); fixtureState(app, "selection", selected); app.showImportedVoice(packs.first().id, android.net.Uri.fromFile(invalid)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.FAILED }
            capture("invalid-wav-error")
            ui.onNodeWithTag("sheet-close").performClick(); assertNull(app.recordingDialog)
        } finally { ui.runOnIdle { app.recordingDialog?.close(); app.sheet = null; fixtureState(app, "packs", packs); fixtureState(app, "selection", selected); app.setAppearance(mode) }; invalid.delete() }
    }
}
