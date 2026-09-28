package org.pockettts.android.engine

/** Streaming, bounded envelope. Adjacent bins merge as audio grows; PCM is never retained. */
internal class AudioPeaks {
    private val peaks = FloatArray(512)
    private var used = 0
    private var width = 1200L
    private var pending = 0L
    private var peak = 0f
    var samples = 0L
        private set
    fun add(audio: FloatArray) {
        for (value in audio) {
            peak = maxOf(peak, if (value.isFinite()) kotlin.math.abs(value).coerceAtMost(1f) else 0f)
            pending++; samples++
            if (pending == width) {
                peaks[used++] = peak; peak = 0f; pending = 0
                if (used == peaks.size) {
                    for (i in 0 until used / 2) peaks[i] = maxOf(peaks[i * 2], peaks[i * 2 + 1])
                    used /= 2; width *= 2
                }
            }
        }
    }
    fun snapshot(): List<Float> = List(used + if (pending > 0) 1 else 0) { if (it < used) peaks[it] else peak }
    fun binSamples(): Long = width
}
