package org.pockettts.android.engine

import android.content.Context
import kotlin.math.roundToInt

internal object PlaybackSpeed {
    fun normalize(value: Float): Float = if (value.isFinite()) (value.coerceIn(.5f, 2f) * 20).roundToInt() / 20f else 1f
    fun label(value: Float) = String.format(java.util.Locale.ROOT, "%.2f×", value)
    fun read(context: Context): Float {
        val prefs = context.getSharedPreferences("pockettts_reader", Context.MODE_PRIVATE)
        return normalize(if (prefs.contains("playback_rate")) prefs.getFloat("playback_rate", 1f)
            else floatArrayOf(.75f, 1f, 1.25f, 1.5f, 2f)[prefs.getInt("speed", 1).coerceIn(0, 4)])
    }
    fun save(context: Context, value: Float) {
        context.getSharedPreferences("pockettts_reader", Context.MODE_PRIVATE).edit().putFloat("playback_rate", normalize(value)).apply()
    }
}
