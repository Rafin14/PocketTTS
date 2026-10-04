package org.pockettts.android.engine

import kotlin.math.abs

/** Gain on normalized PCM, with a shared peak ceiling instead of hard clipping. */
internal object PcmGain {
    fun apply(samples: FloatArray, requested: Float): Float {
        require(requested.isFinite() && requested in .5f..2f)
        require(samples.all { it.isFinite() })
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (peak == 0f) requested else minOf(requested, .98f / peak)
        for (index in samples.indices) samples[index] *= gain
        return gain
    }
}
