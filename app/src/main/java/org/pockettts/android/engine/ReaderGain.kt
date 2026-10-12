package org.pockettts.android.engine

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Playback-only preferences and streaming transforms; never edits reference or dry chunk files. */
internal object ReaderGain {
    private fun prefs(context: Context) = context.getSharedPreferences("reader_voice_gain", Context.MODE_PRIVATE)
    fun get(context: Context, key: String): Float = prefs(context).getFloat(key, 0f).let {
        if (it.isFinite()) it.coerceIn(PcmGain.MIN_DB, PcmGain.MAX_DB) else 0f
    }
    fun set(context: Context, key: String, db: Float) {
        require(db.isFinite())
        prefs(context).edit().putFloat(key, db.coerceIn(PcmGain.MIN_DB, PcmGain.MAX_DB)).apply()
    }
    fun remove(context: Context, key: String) { prefs(context).edit().remove(key).apply() }

    fun render(source: File, target: File, db: Float, cancelled: () -> Boolean): Float {
        require(source.canonicalFile != target.canonicalFile)
        val requested = PcmGain.linear(db)
        var peak = 0f
        fun read(input: RandomAccessFile, block: (FloatArray) -> Unit) {
            val header = ByteArray(44); input.readFully(header)
            require(header.contentEquals(PcmWav.header(input.length()-44))) { "Unexpected Reader audio format" }
            val bytes = ByteArray(8192)
            while (true) {
                check(!cancelled()) { "Playback gain cancelled" }
                val count = input.read(bytes); if (count < 0) break
                require(count % 2 == 0)
                block(FloatArray(count/2) { i ->
                    ((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()/32767f
                })
            }
        }
        RandomAccessFile(source, "r").use { read(it) { pcm -> pcm.forEach { peak=maxOf(peak, abs(it)) } } }
        val gain = if (peak == 0f) requested else minOf(requested, .98f/peak)
        try {
            PcmWav.Writer(target).use { output -> RandomAccessFile(source, "r").use { input ->
                read(input) { pcm -> pcm.indices.forEach { pcm[it]*=gain }; output.write(pcm) }
            } }
            check(!cancelled()) { "Playback gain cancelled" }
        } catch (error: Throwable) { target.delete(); throw error }
        return gain
    }
}
