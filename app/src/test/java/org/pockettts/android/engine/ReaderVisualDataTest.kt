package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test

class ReaderVisualDataTest {
    @Test fun exactOffsetsIncludeRepeatedTextAndUnicode() {
        val text = "  Same sentence.\r\n\r\nSame sentence. 😀 " .repeat(3000)
        val ranges = ReaderTextChunker.ranges(text, 80).toList()
        assertEquals(ReaderTextChunker.chunk(text, 80), ranges.map { text.substring(it.start, it.end) })
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertEquals(text.filterNot { it.isWhitespace() }, ranges.joinToString("") { text.substring(it.start, it.end) }.filterNot { it.isWhitespace() })
    }
    @Test fun envelopeStaysBoundedAndPreservesActualPeaks() {
        val peaks = AudioPeaks()
        repeat(2000) { peaks.add(FloatArray(2400) { if (it == 21) -.8f else .1f }) }
        assertEquals(4_800_000L, peaks.samples)
        assertTrue(peaks.snapshot().size <= 512)
        assertTrue(peaks.snapshot().all { it == .8f })
        assertTrue(peaks.binSamples() > 1200)
        val silence = AudioPeaks().apply { add(FloatArray(2400)) }
        assertEquals(listOf(0f, 0f), silence.snapshot())
    }
}
