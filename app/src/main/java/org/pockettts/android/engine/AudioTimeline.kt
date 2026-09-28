package org.pockettts.android.engine

/** Coordinates generated chunk durations with a document-wide media position. */
internal class AudioTimeline {
    private val ends = mutableListOf<Long>()
    val duration: Long get() = ends.lastOrNull() ?: 0
    val size: Int get() = ends.size
    fun append(durationMs: Long) { require(durationMs > 0); ends += duration + durationMs }
    fun start(index: Int): Long = if (index == 0) 0 else ends[index - 1]
    fun locate(position: Long): Pair<Int, Long> {
        check(ends.isNotEmpty())
        val clamped = position.coerceIn(0, (duration - 1).coerceAtLeast(0))
        val index = ends.indexOfFirst { clamped < it }
        return index to clamped - start(index)
    }
}
