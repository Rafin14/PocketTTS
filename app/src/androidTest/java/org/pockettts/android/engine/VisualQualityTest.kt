package org.pockettts.android.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Same fixtures and interactions for baseline and refined screenshots. QA package only. */
class VisualQualityTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private fun ready() = ui.waitUntil(60000) { ui.activity.documentReady }
    private fun capture(name: String) {
        ui.waitForIdle(); SystemClock.sleep(500); ui.waitForIdle()
        val stage = args.getString("stage", "before")
        val config = args.getString("config")
        require(config == null || config.matches(Regex("[a-z0-9.-]+")))
        val dir = File(ui.activity.getExternalFilesDir(null), "visual-quality/$stage" + if (config == null) "" else "/$config").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val resources = ui.activity.resources
        File(dir, "$name.json").writeText(org.json.JSONObject()
            .put("theme", ui.activity.appearance).put("systemDark", resources.configuration.uiMode and 0x30 == 0x20)
            .put("width", bitmap.width).put("height", bitmap.height).put("densityDpi", resources.displayMetrics.densityDpi)
            .put("fontScale", resources.configuration.fontScale).put("api", android.os.Build.VERSION.SDK_INT)
            .put("package", ui.activity.packageName).put("variant", "debug QA").toString(2))
        bitmap.recycle()
    }
    private fun close() = ui.runOnIdle {
        ui.activity.sheet = null; ui.activity.detail = null; ui.activity.renameTarget = null
        ui.activity.confirmation = null; ui.activity.recordingDialog?.close(); ui.activity.stopPreview()
    }
    private fun scroll(tag: String) {
        val dialog = ui.activity.recordingDialog
        if (tag in listOf("edit-volume", "normalize-volume") && dialog?.phase == VoiceRecordingDialog.Phase.TRIMMING) {
            ui.runOnIdle { dialog.applyTrim(false) }
            ui.waitUntil(120000) { dialog.phase == VoiceRecordingDialog.Phase.STOPPED }
        }
        if (tag.startsWith("trim-") && dialog?.phase == VoiceRecordingDialog.Phase.STOPPED) ui.runOnIdle { dialog.adjustTrim() }
        ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag(tag))
    }

    @Test fun inventory() {
        ready(); val app = ui.activity
        check(app.packageName.endsWith(".qa")) { "Disposable QA package required" }
        val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val previous = app.selection; val mode = app.appearance; val text = app.editor.text.toString(); val editing = app.editing
        val source = File(app.cacheDir, "visual-synthetic.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(24000 * 6) { kotlin.math.sin(it * .15).toFloat() * .1f }) }
        var voice = ModelPackRepository.importRecording(app, pack.id, "A calm expressive reference voice for reading longer documents with clear delivery", source).voices.last()
        try {
            for (theme in listOf("dark", "light", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme); app.selectVoice(pack, voice); app.switchTab(0); app.setEditing(false); app.editor.setText("A quiet afternoon by the river.\n".repeat(200)) }
                capture("$theme-reader-long-name")
                for (sheet in listOf("voice", "speed", "gain")) {
                    ui.runOnIdle { app.sheet = sheet }; capture("$theme-reader-$sheet"); close()
                }
                ui.runOnIdle { app.switchTab(1) }
                ui.onNodeWithTag("voices-list").performScrollToNode(hasTestTag("voice-${voice.id}"))
                capture("$theme-voices-selected")
                ui.onNodeWithTag("voice-actions-${voice.id}").performClick(); capture("$theme-voice-menu")
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ui.runOnIdle { app.detail = pack to voice }; capture("$theme-details-top")
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(app.getString(R.string.voice_save_wav_action)))
                capture("$theme-details-actions"); close()
                ui.runOnIdle { app.beginRename(pack, voice) }; capture("$theme-rename"); close()
                ui.runOnIdle { app.deleteVoice(pack, voice) }; capture("$theme-delete-confirmation"); close()
                ui.runOnIdle { app.previewVoice(pack, voice) }
                ui.waitUntil(10000) { app.preview.state.key != null && !app.preview.state.loading }
                capture("$theme-preview"); close()
                ui.runOnIdle { app.sheet = "add" }; capture("$theme-add-top")
                scroll("add-video"); capture("$theme-add-bottom"); close()
                ui.runOnIdle { app.addVoice(pack, true) }; capture("$theme-record-permission"); close()
                ui.runOnIdle { app.showImportedVoice(pack.id, Uri.fromFile(source)) }
                ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
                capture("$theme-trim-top"); scroll("edit-volume"); capture("$theme-trim-gain")
                scroll("trim-preview"); capture("$theme-trim-bottom"); close()
                ui.runOnIdle { app.editVoice(pack, voice) }
                ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
                capture("$theme-edit-top"); scroll("normalize-volume"); capture("$theme-edit-gain"); close()
                ui.runOnIdle { app.switchTab(2) }; capture("$theme-settings-top")
                ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("about-entry")); capture("$theme-settings-bottom")
                for (sheet in listOf("appearance", "engine", "about")) {
                    ui.runOnIdle { app.sheet = sheet }; capture("$theme-$sheet-top")
                    if (sheet == "about") { scroll("about-notices"); capture("$theme-about-bottom") }
                    close()
                }
            }
        } finally {
            close(); ModelPackRepository.deleteVoice(app, pack.id, voice.id); source.delete()
            ui.runOnIdle { app.setAppearance(mode); app.editor.setText(text); app.setEditing(editing); previous?.let { app.selectVoice(it.first, it.second) } }
        }
    }

    @Test fun namesAndKeyboard() {
        ready(); val app = ui.activity; check(app.packageName.endsWith(".qa"))
        val previous = app.selection; val mode = app.appearance; val text = app.editor.text.toString(); val editing = app.editing
        val pack = app.selection!!.first; val base = app.selection!!.second
        val source = File(app.cacheDir, "names-synthetic.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(24000*4) { kotlin.math.sin(it*.15).toFloat()*.1f }) }
        var voice = ModelPackRepository.importRecording(app, pack.id, "Names QA ${System.nanoTime()}", source).voices.last()
        try {
            ui.runOnIdle { app.setAppearance("dark"); app.switchTab(0); app.setEditing(false) }
            val names = listOf("Short", "Twenty character vox", "An expressive reference voice for reading".take(40), "A calm expressive reference voice for reading longer documents with clear delivery".take(80), "W".repeat(80), "বাংলা 日本語 العربية English voice")
            names.forEachIndexed { index, name ->
                voice = ModelPackRepository.renameVoice(app, pack.id, voice.id, name).voices.first { it.id == voice.id }
                ui.runOnIdle { app.selectVoice(pack, voice) }
                capture("${args.getString("config", "dark")}-name-$index")
                if (args.getString("stage", "before").endsWith("after")) {
                    if (runCatching { ui.onNodeWithTag("reader-voice").assertIsDisplayed() }.isFailure)
                        ui.onNodeWithTag("reader-scroll").performScrollToNode(hasTestTag("reader-voice"))
                    ui.onNodeWithTag("reader-voice").performClick()
                    ui.onNodeWithTag("glass-sheet").assertIsDisplayed()
                    ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(name))
                    ui.onNode(hasText(name) and hasAnyAncestor(hasTestTag("sheet-content"))).assertIsDisplayed()
                    capture("${args.getString("config", "dark")}-full-name-$index"); close()
                }
            }
            ui.runOnIdle { app.editor.setText("Keyboard editing remains available."); app.setEditing(true); app.editor.requestFocus() }
            ui.runOnIdle { (app.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(app.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            SystemClock.sleep(800); capture("${args.getString("config", "dark")}-reader-keyboard")
            ui.runOnIdle { app.editor.setText(" ") }
            val play = hasText(app.getString(R.string.reader_play)) and hasClickAction()
            if (runCatching { ui.onNode(play).assertIsDisplayed() }.isFailure)
                ui.onNodeWithTag("reader-scroll").performScrollToNode(play)
            ui.onNode(play).performClick()
            ui.runOnIdle { assertEquals(ReaderPlaybackService.State.ERROR, app.snapshot.state) }
            capture("${args.getString("config", "dark")}-keyboard-play-validation")
            ui.runOnIdle { app.setEditing(false) }
        } finally { close(); ModelPackRepository.deleteVoice(app, pack.id, voice.id); source.delete(); ui.runOnIdle { app.setAppearance(mode); app.editor.setText(text); app.setEditing(editing); previous?.let { app.selectVoice(it.first, it.second) } } }
    }

    @Test fun responsiveControls() {
        ready(); val app = ui.activity; check(app.packageName.endsWith(".qa"))
        val mode = app.appearance; val pack = app.selection!!.first
        val source = File(app.cacheDir, "responsive-synthetic.wav")
        PcmWav.Writer(source).use { it.write(FloatArray(24000*6) { kotlin.math.sin(it*.15).toFloat()*.1f }) }
        val prefix = args.getString("config", "default")
        try {
            ui.runOnIdle { app.setAppearance("dark"); app.switchTab(0); app.sheet = "speed" }
            ui.onNodeWithTag("speed-reset").performClick(); assertEquals(1f, app.speed)
            capture("$prefix-speed"); close()
            ui.runOnIdle { app.sheet = "add" }; scroll("add-video"); capture("$prefix-add"); close()
            ui.runOnIdle { app.showImportedVoice(pack.id, Uri.fromFile(source)) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            scroll("trim-preview"); capture("$prefix-trim-steps")
            scroll("normalize-volume"); capture("$prefix-trim-gain")
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(app.getString(R.string.ui_help_show)))
            ui.onNodeWithText(app.getString(R.string.ui_help_show)).performClick()
            capture("$prefix-trim-help"); close()
            ui.runOnIdle { app.switchTab(2) }; capture("$prefix-settings")
            for (tag in listOf("backup-export", "backup-import")) {
                ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
                // A large-font card may exceed the viewport; reveal the child, not only its item.
                ui.onNodeWithTag(tag).performScrollTo()
                // The compact painted surface is smaller than Material's expanded hit region.
                val bounds = ui.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
                val density = app.resources.displayMetrics.density
                assertTrue("Touch height $tag: ${bounds.height / density}", bounds.height / density >= 47.9f)
                assertTrue("Touch width $tag: ${bounds.width / density}", bounds.width / density >= 47.9f)
            }
            capture("$prefix-settings-actions")
        } finally { close(); source.delete(); ui.runOnIdle { app.setAppearance(mode) } }
    }
}
