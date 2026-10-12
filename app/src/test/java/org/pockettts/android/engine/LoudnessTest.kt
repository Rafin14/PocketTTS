package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LoudnessTest {
    private fun tone(amplitude: Float, frequency: Int = 1000, seconds: Int = 3) =
        FloatArray(PcmWav.RATE*seconds) { (sin(2*PI*frequency*it/PcmWav.RATE)*amplitude).toFloat() }

    @Test fun kWeightingAndAmplitudeAreCalibrated() {
        val a = Loudness.measure(tone(.1f))
        val b = Loudness.measure(tone(.2f))
        // BS.1770 mono 1 kHz sine, peak -20 dBFS, is approximately -23 LUFS.
        assertEquals(-23.0, a.lufs, .12)
        assertEquals(20*log10(2.0), b.lufs-a.lufs, .002)
        assertTrue(a.estimatedPeak >= a.samplePeak)
    }

    @Test fun samePeaksDoNotMeanSameLoudness() {
        val lowFrequency = Loudness.measure(tone(.1f, 100))
        val high = tone(.1f, 4000)
        val peak = high.maxOf { abs(it) }
        high.indices.forEach { high[it] *= .1f/peak }
        val highFrequency = Loudness.measure(high)
        assertEquals(lowFrequency.samplePeak, highFrequency.samplePeak, .002)
        assertTrue(highFrequency.lufs-lowFrequency.lufs > 3)
    }

    @Test fun normalizationIsAbsoluteBoundedAndPeakSafe() {
        val source=tone(.15f)
        val adjustment=Loudness.adjustment(source, -20.0)
        val repeated=Loudness.adjustment(source, -20.0)
        assertEquals(adjustment.gainDb, repeated.gainDb, 0f)
        val result=source.copyOf(); PcmGain.apply(result, PcmGain.linear(adjustment.gainDb))
        val measured=Loudness.measure(result)
        assertEquals(-20.0, measured.lufs, .02)
        assertTrue(measured.estimatedPeak < .9)
        val loud=tone(.95f); val limited=Loudness.adjustment(loud, -10.0)
        assertTrue(limited.limited)
        PcmGain.apply(loud, PcmGain.linear(limited.gainDb))
        assertTrue(Loudness.measure(loud).estimatedPeak <= .881)
        println("Loudness target=-20 achieved=${measured.lufs} estimatedPeak=${measured.estimatedPeak}")
    }

    @Test fun silenceNearSilenceNoiseClippingAndCancellation() {
        assertTrue(runCatching { Loudness.adjustment(FloatArray(72000), -20.0) }.isFailure)
        assertTrue(runCatching { Loudness.adjustment(tone(.000001f), -20.0) }.isFailure)
        val noise=FloatArray(72000) { java.util.Random(it.toLong()).nextFloat()*.02f-.01f }
        assertTrue(Loudness.adjustment(noise, -20.0).gainDb <= PcmGain.MAX_DB)
        val clipped=tone(2f).apply { indices.forEach { this[it]=this[it].coerceIn(-1f,1f) } }
        assertTrue(Loudness.adjustment(clipped, -20.0).limited)
        assertTrue(runCatching { Loudness.measure(tone(.1f), cancelled={true}) }.isFailure)
        assertTrue(runCatching { Loudness.measure(floatArrayOf(Float.NaN)) }.isFailure)
        val short=FloatArray(72000) { if(it in 12000..36000) sin(it*.2).toFloat()*.1f else 0f }
        assertTrue(Loudness.measure(short).lufs.isFinite())
    }

    @Test fun decibelConversionDoesNotChangeSpeedScope() {
        assertEquals(.5f, PcmGain.linear(PcmGain.MIN_DB), .00001f)
        assertEquals(1f, PcmGain.linear(0f), 0f)
        assertEquals(2f, PcmGain.linear(PcmGain.MAX_DB), .00001f)
        assertEquals(1f, PlaybackSpeed.normalize(1f), 0f)
    }
}
