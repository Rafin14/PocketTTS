package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test

class PcmGainTest {
    @Test fun amplifiesPcmWithoutClippingAndPreservesSigns() {
        val low = floatArrayOf(-.2f, 0f, .1f)
        assertEquals(2f, PcmGain.apply(low, 2f), .0001f)
        assertArrayEquals(floatArrayOf(-.4f, 0f, .2f), low, .0001f)
        val high = floatArrayOf(-.8f, .4f)
        assertEquals(1.225f, PcmGain.apply(high, 2f), .0001f)
        assertArrayEquals(floatArrayOf(-.98f, .49f), high, .0001f)
        assertEquals(2f, PcmGain.apply(FloatArray(10), 2f), 0f)
        assertTrue(runCatching { PcmGain.apply(floatArrayOf(Float.NaN), 1f) }.isFailure)
        assertTrue(runCatching { PcmGain.apply(low, Float.POSITIVE_INFINITY) }.isFailure)
    }
}
