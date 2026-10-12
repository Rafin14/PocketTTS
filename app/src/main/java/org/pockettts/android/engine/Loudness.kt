package org.pockettts.android.engine

import kotlin.math.*

/** Mono BS.1770 K weighting and dual-gated integrated loudness; no compression. */
internal object Loudness {
    class SilentInput : IllegalArgumentException("Audio is silent or too quiet to normalize safely.")
    data class Measurement(val lufs: Double, val samplePeak: Double, val estimatedPeak: Double)
    data class Adjustment(val gainDb: Float, val limited: Boolean, val measurement: Measurement)

    fun measure(samples: FloatArray, rate: Int = PcmWav.RATE, cancelled: () -> Boolean = { false }): Measurement {
        require(rate in 8000..192000 && samples.size <= rate * 30 && samples.all { it.isFinite() })
        val k = tan(PI * 1681.974450955533 / rate)
        val q = .7071752369554196
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(.4996667741545416)
        val a0 = 1 + k / q + k * k
        val shelf = Filter((vh + vb*k/q + k*k)/a0, 2*(k*k-vh)/a0,
            (vh-vb*k/q+k*k)/a0, 2*(k*k-1)/a0, (1-k/q+k*k)/a0)
        val r = tan(PI * 38.13547087602444 / rate)
        val d = 1 + r/.5003270373238773 + r*r
        val highPass = Filter(1.0, -2.0, 1.0, 2*(r*r-1)/d, (1-r/.5003270373238773+r*r)/d)
        val window = (rate * .4).roundToInt(); val hop = (rate * .1).roundToInt()
        val ring = DoubleArray(window); val blocks = ArrayList<Double>()
        var sum = 0.0; var peak = 0.0
        samples.forEachIndexed { index, sample ->
            if (index % 4096 == 0) check(!cancelled()) { "Audio processing cancelled" }
            peak = max(peak, abs(sample.toDouble()))
            val weighted = highPass.next(shelf.next(sample.toDouble()))
            val energy = weighted * weighted
            val slot = index % window; sum += energy - ring[slot]; ring[slot] = energy
            if (index + 1 >= window && (index + 1 - window) % hop == 0) blocks += max(0.0, sum/window)
        }
        val absolute = blocks.filter { lufs(it) > -70 }
        val relative = if (absolute.isEmpty()) emptyList() else absolute.filter { lufs(it) > lufs(absolute.average()) - 10 }
        val integrated = if (relative.isEmpty()) Double.NEGATIVE_INFINITY else lufs(relative.average())
        return Measurement(integrated, peak, estimatedPeak(samples, peak, cancelled))
    }

    fun adjustment(samples: FloatArray, target: Double, cancelled: () -> Boolean = { false }): Adjustment {
        require(target.isFinite() && target in -40.0..-10.0)
        val m = measure(samples, cancelled = cancelled)
        if (!m.lufs.isFinite() || m.lufs < -60) throw SilentInput()
        val desired = target - m.lufs
        // Conservative intersample estimate with margin; not certified dBTP metering.
        val peakLimit = if (m.estimatedPeak > 0) 20 * log10(.88 / m.estimatedPeak) else 0.0
        val gain = min(desired.coerceIn(PcmGain.MIN_DB.toDouble(), PcmGain.MAX_DB.toDouble()), peakLimit)
            .coerceAtLeast(PcmGain.MIN_DB.toDouble()).toFloat()
        return Adjustment(gain, abs(gain - desired) > .1 || m.samplePeak >= .98, m)
    }

    private fun lufs(energy: Double) = if (energy <= 0) Double.NEGATIVE_INFINITY else -.691 + 10*log10(energy)
    private class Filter(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun next(x: Double): Double {
            val y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
            x2=x1; x1=x; y2=y1; y1=y; return y
        }
    }

    private fun estimatedPeak(samples: FloatArray, samplePeak: Double, cancelled: () -> Boolean): Double {
        // Four-phase, 24-tap Blackman-windowed sinc. Fixed kernels; bounded work/memory.
        val kernels = (1..3).map { phase ->
            DoubleArray(24) { tap ->
                val t = tap - 11 - phase/4.0
                val sinc = if (abs(t) < 1e-9) 1.0 else sin(PI*t)/(PI*t)
                sinc * (.42 + .5*cos(PI*t/12) + .08*cos(2*PI*t/12))
            }.also { kernel -> val total=kernel.sum(); kernel.indices.forEach { kernel[it]/=total } }
        }
        var peak = samplePeak
        for (index in samples.indices) {
            if (index % 4096 == 0) check(!cancelled()) { "Audio processing cancelled" }
            kernels.forEach { kernel ->
                var interpolated=0.0
                kernel.indices.forEach { tap -> val at=index+tap-11; if (at in samples.indices) interpolated+=samples[at]*kernel[tap] }
                peak=max(peak, abs(interpolated))
            }
        }
        return peak * 1.02
    }
}
