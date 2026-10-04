package org.pockettts.android.engine

import java.io.File

/** Background-only WAV scan/selection using the decoder already bundled for DeepFilter. */
internal object WavSamples {
    init { System.loadLibrary("deepfilter_jni") }
    data class Info(val durationMs: Int, val peaks: List<Float>)
    fun inspect(file: File, cancellation: java.util.concurrent.atomic.AtomicBoolean? = null): Info {
        PcmWav.validateVoiceSample(file)
        val data = nativeInspect(file.absolutePath, PcmWav.MAX_SOURCE_SECONDS, cancellation)
        return Info(data[0].toInt(), data.drop(1))
    }
    fun trim(source: File, target: File, startMs: Int = 0, endMs: Int = -1, gain: Float = 1f): Float {
        require(source.canonicalFile != target.canonicalFile)
        PcmWav.validateVoiceSample(source)
        val pcm = nativeDecode(source.absolutePath, startMs, endMs)
        require(pcm.size in 72000..720000 && pcm.all { it.isFinite() })
        val applied = if (gain == 1f) 1f else PcmGain.apply(pcm, gain)
        try { PcmWav.Writer(target).use { it.write(pcm) } }
        catch (error: Throwable) { target.delete(); throw error }
        return applied
    }
    private external fun nativeInspect(path: String, maxSeconds: Int, cancellation: java.util.concurrent.atomic.AtomicBoolean?): FloatArray
    private external fun nativeDecode(path: String, startMs: Int, endMs: Int): FloatArray
}
