package org.pockettts.android.engine

import kotlin.math.abs
import kotlin.math.pow

/** Gain on normalized PCM, with a shared peak ceiling instead of hard clipping. */
internal object PcmGain {
    const val MIN_DB = -6.0206f
    const val MAX_DB = 6.0206f
    fun linear(db: Float): Float = 10.0.pow(db.coerceIn(MIN_DB, MAX_DB) / 20.0).toFloat().coerceIn(.5f, 2f)
    fun db(linear: Float): Float = (20 * kotlin.math.log10(linear.toDouble())).toFloat()
    fun label(db: Float): String = String.format(java.util.Locale.getDefault(), "%+.1f dB", db)
    fun apply(samples: FloatArray, requested: Float): Float {
        require(requested.isFinite() && requested in .5f..2f)
        require(samples.all { it.isFinite() })
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (peak == 0f) requested else minOf(requested, .98f / peak)
        for (index in samples.indices) samples[index] *= gain
        return gain
    }
}
