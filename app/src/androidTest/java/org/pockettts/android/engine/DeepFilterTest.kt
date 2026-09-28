package org.pockettts.android.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

class DeepFilterTest {
    @Test fun bundledModelRepeatedInferenceAndOriginalPreservation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "df-test-original.wav")
        val enhanced = File(context.cacheDir, "df-test-enhanced.wav")
        val random = java.util.Random(7)
        val pcm = FloatArray(PcmWav.RATE * 3) {
            (.1 * sin(it * 2 * Math.PI * 220 / PcmWav.RATE) + .04 * (random.nextDouble() - .5)).toFloat()
        }
        PcmWav.Writer(original).use { it.write(pcm) }
        val raw = original.readBytes()
        try {
            DeepFilterNet3Denoiser(context).use { denoiser ->
                repeat(3) {
                    val (before, after) = denoiser.processWav(original, enhanced)
                    assertEquals(48, before.size); assertEquals(48, after.size)
                    assertTrue(PcmWav.isVoiceSample(enhanced))
                    assertEquals(original.length(), enhanced.length())
                    assertArrayEquals(raw, original.readBytes())
                    assertFalse(raw.contentEquals(enhanced.readBytes()))
                    assertTrue(DeepFilterNet3Denoiser.readRecording(enhanced).all { it.isFinite() })
                }
                assertTrue(runCatching { denoiser.processWav(original, original) }.isFailure)
            }
            // A fresh recording flow can reopen the model after releasing it.
            // Damage only the disposable extracted copy: initialization must repair it.
            val cachedModel = File(context.noBackupFilesDir, "deepfilter-${DeepFilterNet3Denoiser.MODEL_SHA}.onnx")
            java.io.RandomAccessFile(cachedModel, "rw").use { it.writeInt(0) }
            PcmWav.Writer(original).use { writer -> repeat(10) { writer.write(pcm) } }
            DeepFilterNet3Denoiser(context).use { it.processWav(original, enhanced) }
            assertEquals(44L + PcmWav.RATE * 30L * 2, enhanced.length())
            assertTrue(PcmWav.isVoiceSample(enhanced))
        } finally { original.delete(); enhanced.delete() }
    }
}
