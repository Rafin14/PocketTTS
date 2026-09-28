package org.pockettts.android.engine

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** One session per recording workflow. Call only from its serial background worker. */
internal class DeepFilterNet3Denoiser(context: Context) : AutoCloseable {
    private var handle: Long
    init {
        // Bound model memory when a user closes an in-flight flow and immediately opens another.
        sessionSlot.acquire()
        try {
        System.loadLibrary("deepfilter_jni")
        val model = File(context.noBackupFilesDir, "deepfilter-$MODEL_SHA.onnx")
        synchronized(DeepFilterNet3Denoiser::class.java) {
            val installedHash = if (model.isFile) model.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else null
            if (installedHash != MODEL_SHA) {
                val temporary = File.createTempFile("deepfilter-", ".tmp", context.noBackupFilesDir)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.assets.open("deepfilter/deepfilter.onnx").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                digest.update(buffer, 0, size); output.write(buffer, 0, size)
                            }
                        }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA) { "Invalid DeepFilterNet3 model checksum" }
                    check(temporary.renameTo(model)) { "Cannot install DeepFilterNet3 model" }
                } finally { temporary.delete() }
            }
        }
        handle = nativeCreate(model.absolutePath)
        check(handle != 0L) { "DeepFilterNet3 initialization failed" }
        } catch (error: Throwable) { sessionSlot.release(); throw error }
    }
    fun processWav(original: File, enhanced: File): Pair<List<Float>, List<Float>> {
        check(handle != 0L)
        require(original.canonicalFile != enhanced.canonicalFile)
        val input = readRecording(original)
        val before = waveform(input)
        val output = nativeProcess(handle, input)
        require(output.size == input.size && output.all { it.isFinite() })
        try { PcmWav.Writer(enhanced).use { it.write(output) } }
        catch (error: Throwable) { enhanced.delete(); throw error }
        return before to waveform(output)
    }
    fun normalizeImported(original: File, normalized: File) {
        WavSamples.trim(original, normalized)
    }
    override fun close() { if (handle != 0L) { nativeRelease(handle); handle = 0L; sessionSlot.release() } }
    private external fun nativeCreate(path: String): Long
    private external fun nativeProcess(handle: Long, samples: FloatArray): FloatArray
    private external fun nativeRelease(handle: Long)
    companion object {
        private val sessionSlot = java.util.concurrent.Semaphore(1, true)
        const val MODEL_SHA = "e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3"
        // Accepts canonical PCM16 mono 24 kHz only; imported WAVs are normalized
        // separately, never overwriting the original reference file.
        fun readRecording(file: File): FloatArray {
            val bytes = file.length() - 44
            require(bytes in (PcmWav.RATE * 2 * 3L)..(PcmWav.RATE * 2 * 30L) && bytes % 2 == 0L) { "Invalid recording length" }
            return RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(44); input.readFully(header)
                require(header.contentEquals(PcmWav.header(bytes))) { "Expected mono PCM16 WAV at 24000 Hz" }
                val pcm = ByteArray(bytes.toInt()); input.readFully(pcm)
                FloatArray(pcm.size / 2) { index ->
                    ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort() / 32768f
                }
            }
        }
        fun waveform(pcm: FloatArray): List<Float> = List(48) { bar ->
            var peak = 0f
            for (i in bar * pcm.size / 48 until (bar + 1) * pcm.size / 48) peak = maxOf(peak, kotlin.math.abs(pcm[i]))
            peak.coerceIn(0f, 1f)
        }
    }
}
