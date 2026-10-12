package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test

class InteractionFeedbackTest {
    @Test fun quantizationAndLimitsNeverRepeatWhileHeld() {
        assertFalse(FeedbackGate.changed(1f, 1.01f, .05f, .5f, 2f))
        assertTrue(FeedbackGate.changed(1f, 1.05f, .05f, .5f, 2f))
        assertTrue(FeedbackGate.changed(1.99f, 2f, .05f, .5f, 2f))
        assertFalse(FeedbackGate.changed(2f, 2f, .05f, .5f, 2f))
        assertTrue(FeedbackGate.changed(.501f, .5f, .05f, .5f, 2f))
        assertFalse(FeedbackGate.changed(Float.NaN, 1f, .05f, .5f, 2f))
        val gate = FeedbackGate()
        assertTrue(gate.allow(0)); assertFalse(gate.allow(10)); assertFalse(gate.allow(89))
        assertTrue(gate.allow(90)); assertFalse(gate.allow(91)); assertTrue(gate.allow(180))
    }
    @Test fun dragTargetsCancelOutsideAndMirrorForRtl() {
        assertEquals(0, FeedbackGate.destination(20f, 20f, 300, 60))
        assertEquals(1, FeedbackGate.destination(150f, 20f, 300, 60))
        assertEquals(2, FeedbackGate.destination(299f, 59f, 300, 60))
        assertEquals(2, FeedbackGate.destination(20f, 20f, 300, 60, true))
        assertNull(FeedbackGate.destination(-1f, 20f, 300, 60))
        assertNull(FeedbackGate.destination(100f, 60f, 300, 60))
        assertNull(FeedbackGate.destination(300f, 20f, 300, 60))
        assertNull(FeedbackGate.destination(0f, 0f, 0, 0))
    }
    @Test fun confirmedTransportNotBufferingHighlightOrAutomaticChunks() {
        var ticks = 0
        val observer = ReaderHapticObserver { ticks++ }
        val idle = ReaderPlaybackService.State.IDLE
        val loading = ReaderPlaybackService.State.LOADING
        val playing = ReaderPlaybackService.State.PLAYING
        val paused = ReaderPlaybackService.State.PAUSED
        val stopped = ReaderPlaybackService.State.STOPPED
        observer.update(idle); observer.update(loading); assertEquals(0, ticks)
        observer.update(playing); observer.update(playing); assertEquals(1, ticks)
        observer.update(loading); observer.update(playing); assertEquals(1, ticks)
        observer.update(paused); observer.update(paused); assertEquals(2, ticks)
        observer.update(loading); observer.update(playing); assertEquals(3, ticks)
        observer.update(stopped); observer.update(stopped); assertEquals(4, ticks)
        observer.update(idle); observer.update(loading); observer.update(ReaderPlaybackService.State.ERROR)
        assertEquals(4, ticks)
        observer.reset(); observer.update(playing); assertEquals(4, ticks)
    }
}
