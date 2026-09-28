package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun granularBoundedRate() {
        for (step in 10..40) assertEquals(step / 20f, PlaybackSpeed.normalize(step / 20f), .0001f)
        assertEquals(.5f, PlaybackSpeed.normalize(-1f), 0f)
        assertEquals(2f, PlaybackSpeed.normalize(3f), 0f)
        assertEquals(1f, PlaybackSpeed.normalize(Float.NaN), 0f)
        assertEquals(1.25f, PlaybackSpeed.normalize(1.249f), 0f)
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
    }
}
