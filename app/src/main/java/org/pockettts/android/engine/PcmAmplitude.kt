package org.pockettts.android.engine

import kotlin.math.abs

/** Peak of the actual little-endian PCM16 microphone samples, normalized to 0..1. */
internal fun pcmPeak(buffer: ByteArray, count: Int): Float {
    require(count in 0..buffer.size && count % 2 == 0)
    var peak = 0
    for (i in 0 until count step 2) {
        val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
        peak = maxOf(peak, abs(sample))
    }
    return peak / 32768f
}
