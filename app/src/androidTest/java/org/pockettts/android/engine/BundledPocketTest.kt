package org.pockettts.android.engine

import android.content.ContextWrapper
import android.speech.tts.TextToSpeech
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BundledPocketTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun freshLocalExtractionChecksumsAndExistingVoicesPreserved() {
        val directory = File(context.cacheDir, "bundled-qa-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        val ready = BundledPocketTts::class.java.getDeclaredField("ready").apply { isAccessible = true }
        try {
            ready.setBoolean(BundledPocketTts, false)
            BundledPocketTts.ensure(isolated)
            val root = File(directory, "model-packs/${BundledPocketTts.ID}")
            val index = context.assets.open("pockettts/files.tsv").bufferedReader().use { it.readLines() }
            for (line in index) {
                val (path, length, hash) = line.split('\t')
                val file = File(root, path)
                assertEquals(path, length.toLong(), file.length())
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                assertEquals(path, hash, digest.digest().joinToString("") { "%02x".format(it) })
            }
            val manifest = File(root, "manifest.json")
            val custom = manifest.readText().replace("Alba", "Preserved custom name")
            manifest.writeText(custom)
            val timestamp = File(root, "models/flow_lm_main.onnx").lastModified()
            ready.setBoolean(BundledPocketTts, false); BundledPocketTts.ensure(isolated)
            assertEquals(custom, manifest.readText())
            assertEquals(timestamp, File(root, "models/flow_lm_main.onnx").lastModified())
            assertFalse(context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        } finally {
            directory.deleteRecursively()
            ready.setBoolean(BundledPocketTts, false); BundledPocketTts.ensure(context)
        }
    }
    @Test fun systemTtsDiscoversBundledEnglishWithoutPackPicker() {
        BundledPocketTts.ensure(context)
        val done = CountDownLatch(1); var status = -1; var tts: TextToSpeech? = null
        instrumentation.runOnMainSync { tts = TextToSpeech(context, { status = it; done.countDown() }, context.packageName) }
        try {
            assertTrue(done.await(30, TimeUnit.SECONDS)); assertEquals(TextToSpeech.SUCCESS, status)
            val voices = tts!!.voices
            assertTrue(voices.any { it.name == "english-fp32::alba" && !it.isNetworkConnectionRequired })
            assertTrue(tts!!.isLanguageAvailable(java.util.Locale.US) >= TextToSpeech.LANG_AVAILABLE)
        } finally { tts?.shutdown() }
    }
    @Test fun realBundledSynthesisAndCustomReference() {
        assumeTrue("Pocket ONNX synthesis requires a physical ARM64 device, not the emulator ARM translator.",
            !android.os.Build.SUPPORTED_ABIS.first().startsWith("x86"))
        val pack = ModelPackRepository.find(context, BundledPocketTts.ID)!!
        val previousVoice = ModelPackRepository.selectedVoiceId(context, pack)
        val reference = File(context.cacheDir, "bundled-reference-qa.wav")
        File(pack.voicesDir, "alba.wav").copyTo(reference, overwrite = true)
        var created: PackVoice? = null
        try {
            val updated = ModelPackRepository.importRecording(context, pack.id, "Bundled QA ${System.nanoTime()}", reference)
            created = updated.voices.last()
            for (voice in listOf(pack.voices.first(), created)) {
                var samples = 0L
                assertTrue(PocketEngine.withEngine(context, updated) { engine ->
                    engine.synthesize("Hello. This speech is generated offline.", voice.fileName, object : NativePocketTts.AudioSink {
                        override fun onAudio(audio: FloatArray): Boolean { assertTrue(audio.all { it.isFinite() }); samples += audio.size; return true }
                    })
                })
                assertTrue(samples > 2400)
            }
        } finally {
            created?.let { ModelPackRepository.deleteVoice(context, pack.id, it.id) }
            previousVoice?.let { ModelPackRepository.selectVoice(context, pack.id, it) }
            reference.delete()
        }
    }
}
