package org.pockettts.android.engine

import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Recovery reads share the storage monitor; mutation takes engine then storage, never the reverse. */
internal object ManifestStorage {
    val lock = Any()
    fun exists(file: File) = file.isFile || File(file.path + ".bak").isFile

    fun read(file: File): JSONObject = synchronized(lock) {
        val backup = File(file.path + ".bak")
        if (backup.isFile) {
            // Never destroy a valid base by restoring a malformed legacy backup.
            try {
                val json = validate(JSONObject(backup.readText(Charsets.UTF_8)))
                ModelPackRepository.parseManifest(file.parentFile!!, json)
            }
            catch (failure: Exception) {
                Log.e("PocketTTS", "Invalid manifest backup retained: ${file.name}", failure)
                if (file.isFile) return@synchronized validate(JSONObject(file.readText(Charsets.UTF_8)))
                throw IllegalStateException("Voice manifest backup is invalid; existing files were preserved.", failure)
            }
        }
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { validate(JSONObject(it.readText())) }
    }

    private fun validate(json: JSONObject): JSONObject {
        require(json.getInt("format") == 1 && json.getString("id").isNotBlank()) { "Unsupported voice manifest" }
        json.getJSONArray("voices")
        return json
    }

    fun readForWrite(file: File): JSONObject = synchronized(lock) {
        read(file).also {
            // A legacy AtomicFile writer could restore this malformed .bak on failure.
            // Keep both recovery files; reject mutation until the backup is repaired.
            check(!File(file.path + ".bak").exists()) { "Invalid voice manifest backup retained; repair it before saving changes." }
        }
    }
}
