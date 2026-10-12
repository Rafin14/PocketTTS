package org.pockettts.android.engine

import org.json.JSONObject

/** Gain is already rendered into the current WAV. Only the editor uses this recipe. */
internal data class VoiceEdit(
    val startMs: Int, val endMs: Int, val gainDb: Float, val effectiveDb: Float,
    val enhanced: Boolean, val source: String = "original", val targetLufs: Double? = null
) {
    fun json() = JSONObject().put("version", 1).put("source", source)
        .put("startMs", startMs).put("endMs", endMs).put("gainDb", gainDb.toDouble())
        .put("effectiveDb", effectiveDb.toDouble()).put("enhanced", enhanced)
        .put("targetLufs", targetLufs ?: JSONObject.NULL)

    companion object {
        fun read(json: JSONObject?): VoiceEdit? {
            if (json == null) return null
            require(json.getInt("version") == 1) { "Unsupported audio edit version" }
            val start = json.getInt("startMs"); val end = json.getInt("endMs")
            val gain = json.getDouble("gainDb").toFloat(); val effective = json.getDouble("effectiveDb").toFloat()
            val source = json.getString("source")
            require(start >= 0 && end <= PcmWav.MAX_SOURCE_MS && end - start in 3000..30000)
            require(gain.isFinite() && gain in -6.0206f..6.0206f && effective.isFinite() && effective <= gain + .01f)
            require(source == "original" || source == "edit-base")
            val target = if (json.isNull("targetLufs")) null else json.getDouble("targetLufs")
            require(target == null || target.isFinite() && target in -40.0..-10.0)
            return VoiceEdit(start, end, gain, effective, json.getBoolean("enhanced"), source, target)
        }
    }
}
