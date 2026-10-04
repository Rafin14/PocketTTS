package org.pockettts.android.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipOutputStream

/** Portable voice data only. No models, preferences, Reader audio or native caches. */
internal object VoiceBackup {
    private const val FORMAT = "pockettts-voices"
    private const val VERSION = 1
    private const val MAX_VOICES = 1000
    private const val MAX_MANIFEST = 2L * 1024 * 1024
    private const val MAX_ARCHIVE_AUDIO = 4L * 1024 * 1024 * 1024
    private val kinds = setOf("current", "original", "before", "enhanced")
    data class Voice(val packId: String, val language: String, val precision: String,
                     val id: String, val name: String, val edited: Boolean, val audio: Map<String, File>,
                     val sourceId: String = id)
    data class Result(val voices: Int, val copies: Int = 0)

    fun export(context: Context, output: OutputStream, cancelled: () -> Boolean = { false },
               progress: (String) -> Unit = {}): Result = synchronized(PocketEngine) {
        val voices = ModelPackRepository.list(context).flatMap { pack ->
            val records = JSONObject(pack.manifestFile.readText()).getJSONArray("voices")
            val origins = (0 until records.length()).map { records.getJSONObject(it) }
                .associate { it.getString("id") to it.optString("backupSourceId", it.getString("id")) }
            pack.voices.filter { it.userCreated }.map { voice ->
            val audio = linkedMapOf("current" to File(pack.voicesDir, voice.fileName), "original" to ModelPackRepository.originalSource(pack, voice))
            ModelPackRepository.recordingSource(pack, voice, false)?.let { audio["before"] = it }
            ModelPackRepository.recordingSource(pack, voice, true)?.let { audio["enhanced"] = it }
            val origin = origins[voice.id] ?: voice.id
            Voice(pack.id, pack.languageTag, pack.precision, voice.id, voice.displayName, voice.audioEdited, audio, origin)
        } }
        require(voices.size <= MAX_VOICES) { "A backup supports up to $MAX_VOICES voices. Export fewer voices." }
        var total = 0L
        val items = JSONArray()
        val files = linkedMapOf<String, File>()
        voices.forEachIndexed { index, voice ->
            check(!cancelled()) { "Backup cancelled" }
            val audio = JSONObject()
            voice.audio.forEach { (kind, file) ->
                PcmWav.validateVoiceSample(file)
                total += file.length()
                require(total <= MAX_ARCHIVE_AUDIO) { "Voice backup exceeds the 4 GiB audio limit." }
                val path = "voices/$index/$kind.wav"
                files[path] = file
                audio.put(kind, JSONObject().put("path", path).put("bytes", file.length()).put("sha256", hash(file, cancelled)))
            }
            items.put(JSONObject().put("id", voice.id).put("sourceId", voice.sourceId).put("name", voice.name).put("packId", voice.packId)
                .put("languageTag", voice.language).put("precision", voice.precision).put("audioEdited", voice.edited).put("audio", audio))
            progress("Preparing voice ${index + 1} of ${voices.size}")
        }
        val manifest = JSONObject().put("format", FORMAT).put("version", VERSION).put("appVersion", BuildConfig.VERSION_NAME)
            .put("referenceFormat", "wav").put("conditioning", "reference-on-next-synthesis")
            .put("deepFilterModelSha256", DeepFilterNet3Denoiser.MODEL_SHA).put("voiceCount", voices.size).put("voices", items)
            .toString(2).toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
            files.entries.forEachIndexed { index, (path, file) ->
                check(!cancelled()) { "Backup cancelled" }
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { copy(it, zip, file.length(), cancelled) }; zip.closeEntry()
                progress("Exporting audio ${index + 1} of ${files.size}")
            }
        }
        Result(voices.size)
    }

    fun import(context: Context, input: InputStream, cancelled: () -> Boolean = { false },
               progress: (String) -> Unit = {}): Result {
        val staging = File(context.cacheDir, "voice-backup-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Cannot prepare backup import. Check free storage." }
        try {
            var metadata: JSONObject? = null
            val entries = linkedMapOf<String, File>()
            var total = 0L
            // A seekable ZIP validates its central directory as well as local entries;
            // a streaming reader can silently accept an archive truncated at the end.
            val archive = File(staging, "backup.zip")
            input.use { source -> archive.outputStream().use { copy(source, it, MAX_ARCHIVE_AUDIO + MAX_MANIFEST + 16L * 1024 * 1024, cancelled) } }
            ZipFile(archive).use { zip ->
                val directory = zip.entries()
                while (directory.hasMoreElements()) {
                    check(!cancelled()) { "Import cancelled" }
                    val entry = directory.nextElement()
                    val path = entry.name
                    require(!entry.isDirectory && path !in entries && (path == "manifest.json" ||
                        Regex("voices/[0-9]{1,4}/(current|original|before|enhanced)\\.wav").matches(path))) { "Unsafe or unexpected backup entry." }
                    require(entries.size < MAX_VOICES * 4 + 1) { "Too many backup entries." }
                    // Never use an archive pathname as a filesystem target.
                    val target = File(staging, "${entries.size}.data")
                    val limit = if (path == "manifest.json") MAX_MANIFEST else PcmWav.MAX_VOICE_BYTES
                    val crc = CRC32()
                    CheckedInputStream(zip.getInputStream(entry), crc).use { source -> target.outputStream().use { out ->
                        total += copy(source, out, minOf(limit, MAX_ARCHIVE_AUDIO + MAX_MANIFEST - total), cancelled)
                    } }
                    require(target.length() == entry.size && crc.value == entry.crc) { "Corrupt backup ZIP entry." }
                    entries[path] = target
                    if (path == "manifest.json") metadata = JSONObject(target.readText(Charsets.UTF_8))
                    progress("Validating backup audio · ${entries.size - if (metadata != null) 1 else 0} files")
                }
            }
            val root = requireNotNull(metadata) { "Not a Pocket TTS voice backup: manifest missing." }
            require(root.getString("format") == FORMAT) { "Not a Pocket TTS voice backup." }
            require(root.getInt("version") == VERSION) { "Unsupported voice backup version. Update the app before importing." }
            require(root.getString("referenceFormat") == "wav") { "Unsupported voice reference format." }
            val array = root.getJSONArray("voices")
            require(array.length() in 0..MAX_VOICES && root.getInt("voiceCount") == array.length()) { "Invalid backup voice count." }
            val used = mutableSetOf("manifest.json")
            val ids = mutableSetOf<String>()
            val voices = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val id = item.getString("id"); val packId = item.getString("packId"); val name = item.getString("name")
                val sourceId = item.optString("sourceId", id)
                require(safeId(sourceId)) { "Invalid source voice ID in backup." }
                require(safeId(id) && safeId(packId) && ids.add("$packId::$id")) { "Invalid or duplicate voice ID in backup." }
                require(name.trim() == name && name.length in 1..80 && name.none { it.isISOControl() }) { "Invalid voice name in backup." }
                val audio = item.getJSONObject("audio")
                val keys = audio.keys().asSequence().toList()
                require(keys.all { it in kinds } && "current" in keys && "original" in keys) { "Missing or invalid voice audio." }
                val sources = keys.associateWith { kind ->
                    val record = audio.getJSONObject(kind)
                    val path = record.getString("path")
                    require(path == "voices/$index/$kind.wav" && used.add(path)) { "Invalid audio path in backup." }
                    val file = requireNotNull(entries[path]) { "Voice audio is missing from backup." }
                    require(file.length() == record.getLong("bytes") && hash(file, cancelled) == record.getString("sha256")) { "Backup audio checksum mismatch." }
                    PcmWav.validateVoiceSample(file)
                    // Reuse the bounded scanner to reject undecodable/non-finite PCM,
                    // not just an apparently valid WAV header supplied by an archive.
                    WavSamples.inspect(file)
                    check(!cancelled()) { "Import cancelled" }
                    file
                }
                Voice(packId, item.getString("languageTag"), item.getString("precision"), id, name,
                    item.getBoolean("audioEdited"), sources, sourceId)
            }
            require(used == entries.keys) { "Backup contains unreferenced data." }
            check(!cancelled()) { "Import cancelled" }
            progress("Restoring ${voices.size} voices")
            return ModelPackRepository.restoreBackup(context, voices, cancelled)
        } finally { staging.deleteRecursively() } // This operation owns this UUID subtree.
    }

    fun exportWav(context: Context, packId: String, voiceId: String, output: OutputStream,
                  cancelled: () -> Boolean = { false }) = synchronized(PocketEngine) {
        val pack = requireNotNull(ModelPackRepository.find(context, packId)) { "Voice model is unavailable." }
        val voice = pack.voices.firstOrNull { it.id == voiceId } ?: error("Voice is no longer available.")
        val source = File(pack.voicesDir, voice.fileName)
        PcmWav.validateVoiceSample(source)
        source.inputStream().use { copy(it, output, PcmWav.MAX_VOICE_BYTES, cancelled) }
    }

    fun wavName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().trim('.').take(80).ifBlank { "Pocket TTS voice" }.removeSuffix(".wav") + ".wav"

    private fun safeId(value: String) = value.length in 1..100 && Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*").matches(value)
    private fun hash(file: File, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { check(!cancelled()) { "Operation cancelled" }; val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun copy(input: InputStream, output: OutputStream, limit: Long, cancelled: () -> Boolean): Long {
        val buffer = ByteArray(65536); var count = 0L
        while (true) {
            check(!cancelled()) { "Operation cancelled" }
            val size = input.read(buffer); if (size < 0) break
            count += size; require(count <= limit) { "Voice backup exceeds supported size limits." }
            output.write(buffer, 0, size)
        }
        return count
    }
}
