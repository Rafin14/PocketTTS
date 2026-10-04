package org.pockettts.android.engine

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class VoiceBackupTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun ready() = ui.waitUntil(60000) { ui.activity.documentReady }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun fixture(): File = File(ui.activity.cacheDir, "backup-test-${System.nanoTime()}.wav").also { file ->
        PcmWav.Writer(file).use { it.write(FloatArray(120000) { index -> kotlin.math.sin(index * .12).toFloat() * .08f }) }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val folder = File(ui.activity.getExternalFilesDir(null), "voice-backup-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() } }
        return entries
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    @Test fun backupRestoreConflictsMetadataAndCurrentWav() {
        ready(); val app = ui.activity; val selected = app.selection
        val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val originalIds = pack.voices.map { it.id }.toSet()
        val sample = fixture(); val processed = File(app.cacheDir, "backup-gain-${System.nanoTime()}.wav")
        val original = sample.readBytes()
        try {
            var a = ModelPackRepository.importRecording(app, pack.id, "Backup A ${System.nanoTime()}", sample, original = sample).voices.last()
            val b = ModelPackRepository.importRecording(app, pack.id, "Backup B ${System.nanoTime()}", sample, original = sample).voices.last()
            a = ModelPackRepository.renameVoice(app, pack.id, a.id, "Renamed ${a.displayName}").voices.first { it.id == a.id }
            WavSamples.trim(sample, processed, 0, 4000, 2f)
            a = ModelPackRepository.updateVoiceAudio(app, pack.id, a.id, processed, before = processed).voices.first { it.id == a.id }
            val accepted = File(pack.voicesDir, a.fileName).readBytes()
            val archive = ByteArrayOutputStream()
            val exported = VoiceBackup.export(app, archive)
            val entries = unzip(archive.toByteArray())
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(1, manifest.getInt("version")); assertEquals(exported.voices, manifest.getJSONArray("voices").length())
            assertTrue(entries.keys.all { it == "manifest.json" || it.matches(Regex("voices/\\d+/(current|original|before|enhanced)\\.wav")) })
            val wav = ByteArrayOutputStream(); VoiceBackup.exportWav(app, pack.id, a.id, wav)
            assertArrayEquals(accepted, wav.toByteArray())
            val conflict = VoiceBackup.import(app, ByteArrayInputStream(archive.toByteArray()))
            assertEquals(exported.voices, conflict.copies)
            assertArrayEquals(accepted, File(pack.voicesDir, a.fileName).readBytes())
            var now = ModelPackRepository.find(app, pack.id)!!
            val imported = now.voices.first { it.id !in originalIds && it.id != a.id && it.id != b.id && it.displayName.startsWith(a.displayName) }
            assertArrayEquals(accepted, File(pack.voicesDir, imported.fileName).readBytes())
            assertArrayEquals(original, ModelPackRepository.originalSource(now, imported).readBytes())
            assertTrue(JSONObject(now.manifestFile.readText()).getJSONArray("voices").let { array ->
                (0 until array.length()).any { array.getJSONObject(it).optString("backupSourceId") == a.id }
            })
            val repeated = ByteArrayOutputStream(); VoiceBackup.export(app, repeated)
            val repeatedVoices = JSONObject(unzip(repeated.toByteArray()).getValue("manifest.json").toString(Charsets.UTF_8)).getJSONArray("voices")
            assertEquals(a.id, (0 until repeatedVoices.length()).map { repeatedVoices.getJSONObject(it) }.first { it.getString("id") == imported.id }.getString("sourceId"))
            ModelPackRepository.deleteVoice(app, pack.id, a.id); ModelPackRepository.deleteVoice(app, pack.id, b.id)
            VoiceBackup.import(app, ByteArrayInputStream(archive.toByteArray()))
            now = ModelPackRepository.find(app, pack.id)!!
            val restored = now.voices.first { it.id == a.id }
            assertEquals(a.displayName, restored.displayName); assertTrue(restored.audioEdited)
            ui.runOnIdle { app.selectVoice(now, restored); app.switchTab(1); app.previewVoice(now, restored) }
            ui.waitUntil(10000) { app.preview.state.playing }
            ui.onNodeWithTag("preview-waveform").assertExists()
            ui.runOnIdle { app.stopPreview(); app.editVoice(now, restored) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            ui.runOnIdle { app.recordingDialog!!.close() }
            val reset = ModelPackRepository.restoreVoice(app, pack.id, a.id).voices.first { it.id == a.id }
            val resetWav = ByteArrayOutputStream(); VoiceBackup.exportWav(app, pack.id, reset.id, resetWav)
            assertArrayEquals(original, resetWav.toByteArray())
        } finally {
            ui.runOnIdle { app.stopPreview(); app.recordingDialog?.close() }
            ModelPackRepository.find(app, pack.id)!!.voices.filter { it.id !in originalIds }.forEach { ModelPackRepository.deleteVoice(app, pack.id, it.id) }
            ui.runOnIdle { selected?.let { app.selectVoice(it.first, it.second) } }
            sample.delete(); processed.delete()
        }
    }

    @Test fun rejectsUnsafeMissingCorruptUnsupportedAndCancelledBackups() {
        ready(); val app = ui.activity; val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val sample = fixture(); val voice = ModelPackRepository.importRecording(app, pack.id, "Safety ${System.nanoTime()}", sample).voices.last()
        try {
            val output = ByteArrayOutputStream(); VoiceBackup.export(app, output)
            val valid = unzip(output.toByteArray()); val audioPath = valid.keys.first { it.endsWith("current.wav") }
            fun reject(entries: Map<String, ByteArray>) {
                val before = ModelPackRepository.find(app, pack.id)!!.manifestFile.readBytes()
                assertTrue(runCatching { VoiceBackup.import(app, ByteArrayInputStream(zip(entries))) }.isFailure)
                assertArrayEquals(before, ModelPackRepository.find(app, pack.id)!!.manifestFile.readBytes())
                assertFalse(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("voice-backup-") })
            }
            reject(valid + ("../outside.wav" to byteArrayOf(1)))
            reject(valid.filterKeys { it != audioPath })
            reject(valid + (audioPath to valid.getValue(audioPath).copyOf().apply { this[44] = (this[44].toInt() xor 1).toByte() }))
            reject(valid + ("manifest.json" to JSONObject(valid.getValue("manifest.json").toString(Charsets.UTF_8)).put("version", 99).toString().toByteArray()))
            reject(valid + ("manifest.json" to JSONObject(valid.getValue("manifest.json").toString(Charsets.UTF_8)).apply {
                getJSONArray("voices").getJSONObject(0).put("packId", "missing-model")
            }.toString().toByteArray()))
            reject(mapOf("not-a-backup" to byteArrayOf(1)))
            // Removing EOCD used to be accepted by a streaming ZIP reader.
            assertTrue(runCatching { VoiceBackup.import(app, ByteArrayInputStream(output.toByteArray().dropLast(22).toByteArray())) }.isFailure)
            reject(valid + ("manifest.json" to JSONObject(valid.getValue("manifest.json").toString(Charsets.UTF_8)).apply {
                val voices = getJSONArray("voices"); voices.put(voices.getJSONObject(0)); put("voiceCount", voices.length())
            }.toString().toByteArray()))
            assertTrue(runCatching { VoiceBackup.import(app, ByteArrayInputStream(output.toByteArray()), { true }) }.isFailure)
            val before = ModelPackRepository.find(app, pack.id)!!.manifestFile.readBytes()
            val filesBefore = pack.voicesDir.walkTopDown().map { it.relativeTo(pack.voicesDir).path }.toSet()
            val candidate = VoiceBackup.Voice(pack.id, pack.languageTag, pack.precision, voice.id, voice.displayName, false,
                mapOf("current" to sample, "original" to sample))
            var checks = 0
            assertTrue(runCatching { ModelPackRepository.restoreBackup(app, listOf(candidate, candidate)) { ++checks >= 2 } }.isFailure)
            assertArrayEquals(before, ModelPackRepository.find(app, pack.id)!!.manifestFile.readBytes())
            assertEquals(filesBefore, pack.voicesDir.walkTopDown().map { it.relativeTo(pack.voicesDir).path }.toSet())
            assertEquals("A_B_C_.wav", VoiceBackup.wavName("A/B:C?"))
        } finally { ModelPackRepository.deleteVoice(app, pack.id, voice.id); sample.delete() }
    }

    @Test fun volumeEditingRealPcmDenoisePreviewAcceptAndRestore() {
        ready(); val app = ui.activity; val previous = app.selection; val mode = app.appearance
        val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!; val source = fixture()
        val voice = ModelPackRepository.importRecording(app, pack.id, "Volume ${System.nanoTime()}", source).voices.last()
        try {
            ui.runOnIdle { app.editVoice(pack, voice) }
            ui.waitUntil(30000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.TRIMMING }
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }
                ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("edit-volume"))
                ui.onNodeWithTag("edit-volume").assertIsDisplayed()
                capture("volume-$theme")
            }
            ui.runOnIdle { app.recordingDialog!!.changeVolume(2f); app.recordingDialog!!.applyTrim(false) }
            ui.waitUntil(120000) { app.recordingDialog?.phase == VoiceRecordingDialog.Phase.STOPPED }
            val dialog = app.recordingDialog!!
            val amplified = field(dialog, "trimmed") as File
            val a = DeepFilterNet3Denoiser.readRecording(source); val b = DeepFilterNet3Denoiser.readRecording(amplified)
            assertEquals(a.size, b.size); assertEquals(a.maxOrNull()!! * 2, b.maxOrNull()!!, .001f)
            assertTrue(dialog.enhancedReady)
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasTestTag("preview-after"))
            ui.onNodeWithTag("preview-after").performClick()
            val preview = field(dialog, "preview") as SamplePreview
            ui.waitUntil(10000) { preview.state.playing }
            ui.runOnIdle { preview.close() }
            ui.onNodeWithTag("sheet-content").performScrollToNode(hasText(app.getString(R.string.trim_use_before)))
            ui.onNodeWithText(app.getString(R.string.trim_use_before)).performClick()
            ui.onNodeWithTag("record-save").performClick()
            ui.waitUntil(30000) { app.recordingDialog == null }
            val updated = ModelPackRepository.find(app, pack.id)!!.voices.first { it.id == voice.id }
            val actual = DeepFilterNet3Denoiser.readRecording(File(pack.voicesDir, updated.fileName))
            assertEquals(b.maxOrNull()!!, actual.maxOrNull()!!, .0001f)
            val wav = ByteArrayOutputStream(); VoiceBackup.exportWav(app, pack.id, voice.id, wav)
            assertArrayEquals(File(pack.voicesDir, updated.fileName).readBytes(), wav.toByteArray())
            ModelPackRepository.restoreVoice(app, pack.id, voice.id)
            assertArrayEquals(source.readBytes(), ModelPackRepository.originalSource(pack, updated).readBytes())
        } finally {
            ui.runOnIdle { app.recordingDialog?.close(); app.stopPreview(); app.setAppearance(mode) }
            ModelPackRepository.deleteVoice(app, pack.id, voice.id); source.delete()
            ui.runOnIdle { previous?.let { app.selectVoice(it.first, it.second) } }
        }
    }

    @Test fun backupButtonsThemesAndPickerCancellation() {
        ready(); val app = ui.activity; val mode = app.appearance
        try {
            ui.runOnIdle { app.switchTab(2) }
            for (theme in listOf("light", "dark", "amoled")) {
                ui.runOnIdle { app.setAppearance(theme) }
                ui.onNodeWithTag("backup-export").assertIsEnabled(); ui.onNodeWithTag("backup-import").assertIsEnabled()
                capture("backup-$theme")
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.onNodeWithTag("backup-export").performClick()
            fun pickerVisible(): Boolean = automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
            ui.waitUntil(15000) { pickerVisible() }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000) { app.notice.contains("cancelled") }
            ui.onNodeWithTag("backup-import").performClick()
            ui.waitUntil(15000) { pickerVisible() }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000) { app.notice.contains("import cancelled") }
            ui.runOnIdle { app.switchTab(1) }
            ui.onNodeWithTag("voice-actions-alba").performClick()
            ui.onNodeWithText("Save .wav to device").performClick()
            ui.waitUntil(15000) { pickerVisible() }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000) { app.notice.contains("WAV export cancelled") }
        } finally { ui.runOnIdle { app.setAppearance(mode) } }
    }

    @Test fun systemPickerSavesActualZipAndWavAndImportsConflict() {
        ready(); val app = ui.activity; val pack = ModelPackRepository.find(app, BundledPocketTts.ID)!!
        val oldIds = pack.voices.map { it.id }.toSet(); val sample = fixture()
        val voice = ModelPackRepository.importRecording(app, pack.id, "Picker ${System.nanoTime()}", sample).voices.last()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val suffix = java.util.UUID.randomUUID().toString()
        val zipName = "Pocket-backup-test-$suffix.zip"; val wavName = "Pocket-voice-test-$suffix.wav"
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        fun picker() = ui.waitUntil(15000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = null
            ui.waitUntil(15000) { node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString().equals(text, true) }; node != null }
            while (node != null && !node!!.isClickable) node = node!!.parent
            assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun save(name: String) {
            picker()
            fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isEditable || node.className?.toString() == "android.widget.EditText") return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { editable(it)?.let { found -> return found } }
                return null
            }
            var editor: AccessibilityNodeInfo? = null
            ui.waitUntil(15000) { editor = automation.rootInActiveWindow?.let(::editable); editor != null }
            assertTrue(editor!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            click("Save")
            ui.waitUntil(30000) { !app.busy && if (name.endsWith(".wav")) app.notice == "Voice WAV saved." else app.notice.startsWith("Exported") }
        }
        try {
            ui.runOnIdle { app.switchTab(2); app.exportVoices() }; save(zipName)
            val zipBytes = shell("cat /sdcard/Download/$zipName")
            assertEquals("pockettts-voices", JSONObject(unzip(zipBytes).getValue("manifest.json").toString(Charsets.UTF_8)).getString("format"))
            ui.runOnIdle { app.exportVoiceWav(pack, voice) }; save(wavName)
            assertArrayEquals(File(pack.voicesDir, voice.fileName).readBytes(), shell("cat /sdcard/Download/$wavName"))
            ui.runOnIdle { app.importVoices() }; picker(); click(zipName)
            ui.waitUntil(30000) { !app.busy && app.notice.startsWith("Imported") }
            assertTrue(app.notice.contains("conflicts"))
            assertTrue(ModelPackRepository.find(app, pack.id)!!.voices.any { it.id != voice.id && it.displayName.startsWith(voice.displayName) })
        } finally {
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ModelPackRepository.find(app, pack.id)!!.voices.filter { it.id !in oldIds }.forEach { ModelPackRepository.deleteVoice(app, pack.id, it.id) }
            shell("rm /sdcard/Download/$zipName /sdcard/Download/$wavName"); sample.delete()
        }
    }
}
