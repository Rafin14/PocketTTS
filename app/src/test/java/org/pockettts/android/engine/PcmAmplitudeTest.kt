package org.pockettts.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAmplitudeTest {
    @Test fun reflectsRealPcmIncludingSilenceAndNegativeFullScale() {
        assertEquals(0f, pcmPeak(ByteArray(8), 8), 0f)
        assertEquals(.5f, pcmPeak(byteArrayOf(0, 64), 2), 0f)
        assertEquals(1f, pcmPeak(byteArrayOf(0, -128), 2), 0f)
        assertEquals(.25f, pcmPeak(byteArrayOf(0, 32, 0, -128), 2), 0f)
    }
}
