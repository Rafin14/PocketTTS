package org.pockettts.android.engine

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.LocaleList
import android.os.Process
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.FileSystems
import java.util.Locale
import java.util.UUID

internal data class PackVoice(
    val id: String,
    val displayName: String,
    val fileName: String,
    val userCreated: Boolean = false,
    val audioEdited: Boolean = false
)

internal data class ModelPack(
    val id: String,
    val displayName: String,
    val languageTag: String,
    val precision: String,
    val defaultTemperature: Float,
    val defaultLsdSteps: Int,
    val defaultThreads: Int,
    val voices: List<PackVoice>,
    val root: File
) {
    val locale: Locale get() = Locale.forLanguageTag(languageTag)
    val modelsDir: File get() = File(root, "models")
    val voicesDir: File get() = File(root, "voices")
    val manifestFile: File get() = File(root, "manifest.json")
}

internal object ModelPackRepository {
    private const val FORMAT_VERSION = 1
    private const val PREFS = "pockettts_pack_settings"
    private const val SYSTEM_DEFAULT_V1 = "selection.system_default.v1"
    private val sharedModels = listOf("mimi_encoder.onnx", "text_conditioner.onnx", "tokenizer.model")

    fun packsRoot(context: Context): File = File(context.filesDir, "model-packs").apply { mkdirs() }

    fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, "pockettts")
        if (!File(legacy, "models/flow_lm_main.onnx").isFile) return
        val target = File(packsRoot(context), "german-fp32")
        if (File(target, "manifest.json").isFile) return
        if (!target.exists() && !legacy.renameTo(target)) return
        writeManifest(
            ModelPack(
                id = "german-fp32",
                displayName = "Deutsch (FP32)",
                languageTag = "de-DE",
                precision = "fp32",
                defaultTemperature = 0.7f,
                defaultLsdSteps = 1,
                defaultThreads = 2,
                voices = emptyList(),
                root = target
            )
        )
    }

    fun list(context: Context): List<ModelPack> {
        BundledPocketTts.ensure(context)
        migrateLegacy(context)
        return packsRoot(context).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { runCatching { readManifest(it) }.getOrNull() }
            .filter(::isValid)
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    fun find(context: Context, id: String): ModelPack? = list(context).firstOrNull { it.id == id }

    fun applySystemDefaultSelection(context: Context) {
        val preferences = prefs(context)
        if (preferences.getBoolean(SYSTEM_DEFAULT_V1, false)) return
        val preferred = list(context).firstOrNull { it.id == BundledPocketTts.ID }
        preferences.edit()
            .putBoolean(SYSTEM_DEFAULT_V1, true)
            .also { editor -> preferred?.let { editor.putString("selected_pack", it.id) } }
            .apply()
    }

    fun selectedPack(context: Context): ModelPack? {
        val packs = list(context)
        val selected = prefs(context).getString("selected_pack", null)
        return packs.firstOrNull { it.id == selected } ?: packs.firstOrNull { it.id == BundledPocketTts.ID }
    }

    fun selectPack(context: Context, id: String) {
        prefs(context).edit().putString("selected_pack", id).apply()
    }

    fun selectedVoiceId(context: Context, pack: ModelPack): String? {
        val selected = prefs(context).getString("voice.${pack.id}", null)
        return pack.voices.firstOrNull { it.id == selected }?.id ?: pack.voices.firstOrNull()?.id
    }

    fun selectVoice(context: Context, packId: String, voiceId: String) {
        prefs(context).edit().putString("voice.$packId", voiceId).apply()
    }

    fun temperature(context: Context, pack: ModelPack): Float =
        prefs(context).getFloat("temperature.${pack.id}", pack.defaultTemperature)

    fun lsdSteps(context: Context, pack: ModelPack): Int =
        prefs(context).getInt("lsd.${pack.id}", pack.defaultLsdSteps)

    fun threads(context: Context, pack: ModelPack): Int =
        prefs(context).getInt("threads.${pack.id}", pack.defaultThreads)

    fun sentencePauseMs(context: Context, pack: ModelPack): Int =
        prefs(context).getInt("sentence_pause_ms.${pack.id}", DEFAULT_SENTENCE_PAUSE_MS)

    fun maxTextTokens(context: Context, pack: ModelPack): Int =
        prefs(context).getInt("max_text_tokens.${pack.id}", DEFAULT_MAX_TEXT_TOKENS)

    fun saveParameters(
        context: Context,
        pack: ModelPack,
        temperature: Float,
        lsdSteps: Int,
        threads: Int,
        sentencePauseMs: Int,
        maxTextTokens: Int
    ) {
        prefs(context).edit()
            .putFloat("temperature.${pack.id}", temperature.coerceIn(0f, 2f))
            .putInt("lsd.${pack.id}", lsdSteps.coerceIn(1, 8))
            .putInt("threads.${pack.id}", threads.coerceIn(1, 8))
            .putInt("sentence_pause_ms.${pack.id}", sentencePauseMs.coerceIn(0, 2000))
            .putInt("max_text_tokens.${pack.id}", maxTextTokens.coerceIn(10, 200))
            .apply()
    }


    fun importVoice(context: Context, pack: ModelPack, uri: Uri): ModelPack = PocketEngine.updateModels {
        openSelectedDocument(context, uri, R.string.error_open_wav).use { input ->
            addVoice(context, pack.id, queryDisplayName(context, uri).substringBeforeLast('.'), input)
        }
    }

    fun importRecording(context: Context, packId: String, name: String, file: File,
                        original: File? = null, enhanced: File? = null, trimmed: File? = null): ModelPack = PocketEngine.updateModels {
        listOfNotNull(file, original, enhanced, trimmed).forEach {
            require(it.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            require(PcmWav.isVoiceSample(it))
        }
        file.inputStream().use { addVoice(context, packId, name, it, original, enhanced, trimmed) }
    }

    private fun addVoice(context: Context, packId: String, name: String, input: InputStream,
                         original: File? = null, enhanced: File? = null, trimmed: File? = null): ModelPack {
            val pack = find(context, packId) ?: error(context.getString(R.string.status_no_model))
            val displayName = name.trim().take(80).ifBlank { context.getString(R.string.new_voice) }
            val idBase = displayName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .ifBlank { "voice" }
            var id = idBase
            var suffix = 2
            while (pack.voices.any { it.id == id }) id = "$idBase-${suffix++}"
            val targetName = "$id.wav"
            val target = File(pack.voicesDir.apply { mkdirs() }, targetName)
            val archive = recordingArchive(pack, id)
            if (original != null) check(!archive.exists()) { "Recording archive already exists" }
            try {
                copyVoiceLimited(input, target, context.getString(R.string.error_voice_too_large))
                require(PcmWav.isVoiceSample(target)) { context.getString(R.string.error_invalid_wav) }
                if (original != null) {
                    check(archive.mkdirs())
                    original.copyTo(File(archive, "original.wav"))
                    enhanced?.copyTo(File(archive, "enhanced.wav"))
                    trimmed?.copyTo(File(archive, "trimmed.wav"))
                }
            } catch (error: Throwable) {
                target.delete()
                if (original != null) archive.deleteRecursively()
                throw error
            }
            val updated = pack.copy(voices = pack.voices + PackVoice(id, displayName, targetName, true, enhanced != null || trimmed != null))
            try { writeManifest(updated) } catch (error: Throwable) {
                target.delete(); if (original != null) archive.deleteRecursively(); throw error
            }
            selectVoice(context, pack.id, id)
            return updated
    }

    private fun recordingArchive(pack: ModelPack, voiceId: String): File {
        val root = File(pack.voicesDir, ".recordings").canonicalFile
        val folder = File(root, voiceId).canonicalFile
        require(root.parentFile == pack.voicesDir.canonicalFile && folder.parentFile == root)
        return folder
    }
    fun originalSource(pack: ModelPack, voice: PackVoice): File =
        File(recordingArchive(pack, voice.id), "original.wav").takeIf { it.isFile } ?: File(pack.voicesDir, voice.fileName)

    fun recordingSource(pack: ModelPack, voice: PackVoice, enhanced: Boolean): File? {
        val version = File(recordingArchive(pack, voice.id), voice.fileName.removeSuffix(".wav"))
        if (version.isDirectory) return File(version, if (enhanced) "enhanced.wav" else "before.wav").takeIf { it.isFile }
        return (if (!enhanced) File(recordingArchive(pack, voice.id), "trimmed.wav").takeIf { it.isFile } else null)
            ?: File(recordingArchive(pack, voice.id), if (enhanced) "enhanced.wav" else "original.wav").takeIf { it.isFile }
    }

    fun renameVoice(context: Context, packId: String, voiceId: String, name: String): ModelPack = PocketEngine.updateModels {
        val pack = requireNotNull(find(context, packId))
        val voice = pack.voices.first { it.id == voiceId }
        require(voice.userCreated)
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 80) { "Enter a name between 1 and 80 characters." }
        require(pack.voices.none { it.id != voiceId && it.displayName.equals(label, true) }) { "A voice with that name already exists." }
        pack.copy(voices = pack.voices.map { if (it.id == voiceId) it.copy(displayName = label) else it }).also { writeManifest(it) }
    }

    /** New reference + version files first; AtomicFile manifest commits the pointer last. */
    fun updateVoiceAudio(context: Context, packId: String, voiceId: String, sample: File,
                         before: File? = null, enhanced: File? = null, restore: Boolean = false,
                         cancelled: () -> Boolean = { false }): ModelPack = PocketEngine.updateModels {
        val pack = requireNotNull(find(context, packId))
        val voice = pack.voices.first { it.id == voiceId }
        require(voice.userCreated)
        require(PcmWav.isVoiceSample(sample))
        check(!cancelled()) { "Editing cancelled" }
        val old = File(pack.voicesDir, voice.fileName)
        val archive = recordingArchive(pack, voiceId).apply { mkdirs() }
        val original = File(archive, "original.wav")
        if (!original.isFile) {
            val atomic = android.util.AtomicFile(original)
            val output = atomic.startWrite()
            try { old.inputStream().use { it.copyTo(output) }; atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        val target = File(pack.voicesDir, "reference-${UUID.randomUUID()}.wav")
        val version = File(archive, target.nameWithoutExtension)
        var committed = false
        try {
            sample.copyTo(target)
            check(version.mkdirs())
            (before ?: sample).copyTo(File(version, "before.wav"))
            enhanced?.copyTo(File(version, "enhanced.wav"))
            check(!cancelled()) { "Editing cancelled" }
            val updated = pack.copy(voices = pack.voices.map {
                if (it.id == voiceId) it.copy(fileName = target.name, audioEdited = !restore) else it
            })
            writeManifest(updated)
            committed = true
            // No native reader can hold these paths inside updateModels().
            old.delete()
            archive.listFiles()?.filter { it.isDirectory && it != version }?.forEach { it.deleteRecursively() }
            listOf("trimmed.wav", "enhanced.wav").forEach { File(archive, it).delete() }
            updated
        } catch (error: Throwable) {
            if (!committed) { target.delete(); version.deleteRecursively() }
            throw error
        }
    }

    fun restoreVoice(context: Context, packId: String, voiceId: String): ModelPack {
        val pack = requireNotNull(find(context, packId))
        val voice = pack.voices.first { it.id == voiceId }
        return updateVoiceAudio(context, packId, voiceId, originalSource(pack, voice), restore = true)
    }

    /** Validate all packs first, then add copies with rollback; existing references never change. */
    fun restoreBackup(context: Context, voices: List<VoiceBackup.Voice>, cancelled: () -> Boolean): VoiceBackup.Result = PocketEngine.updateModels {
        val packs = list(context).associateBy { it.id }
        voices.forEach { voice ->
            val pack = requireNotNull(packs[voice.packId]) { "Install the matching model before restoring its voices." }
            require(pack.languageTag == voice.language && pack.precision == voice.precision) { "Voice backup model is incompatible." }
        }
        val originals = voices.map { it.packId }.distinct().associateWith { packs.getValue(it).manifestFile.readBytes() }
        val created = mutableListOf<File>()
        var copies = 0
        try {
            voices.groupBy { it.packId }.forEach { (packId, entries) ->
                val pack = packs.getValue(packId)
                val current = pack.voices.toMutableList()
                val provenance = mutableMapOf<String, String>()
                entries.forEach { entry ->
                    check(!cancelled()) { "Import cancelled" }
                    var id = entry.id
                    if (current.any { it.id == id } || recordingArchive(pack, id).exists()) { id = "voice-${UUID.randomUUID()}"; copies++ }
                    var name = entry.name; var suffix = 2
                    while (current.any { it.displayName.equals(name, true) }) {
                        val tail = " (${suffix++})"; name = entry.name.take(80 - tail.length) + tail
                    }
                    val reference = File(pack.voicesDir, "reference-${UUID.randomUUID()}.wav")
                    created += reference; entry.audio.getValue("current").copyTo(reference)
                    val archive = recordingArchive(pack, id)
                    check(!archive.exists()); created += archive; check(archive.mkdirs())
                    entry.audio.getValue("original").copyTo(File(archive, "original.wav"))
                    val version = File(archive, reference.nameWithoutExtension).apply { check(mkdirs()) }
                    (entry.audio["before"] ?: entry.audio.getValue("current")).copyTo(File(version, "before.wav"))
                    entry.audio["enhanced"]?.copyTo(File(version, "enhanced.wav"))
                    current += PackVoice(id, name, reference.name, true, entry.edited)
                    provenance[id] = entry.sourceId
                }
                check(!cancelled()) { "Import cancelled" }
                writeManifest(pack.copy(voices = current), provenance)
            }
            VoiceBackup.Result(voices.size, copies)
        } catch (failure: Throwable) {
            // Restore manifest pointers before removing this operation's files.
            var rolledBack = true
            originals.forEach { (id, bytes) ->
                runCatching {
                    val atomic = android.util.AtomicFile(packs.getValue(id).manifestFile)
                    val output = atomic.startWrite()
                    try { output.write(bytes); atomic.finishWrite(output) } catch (error: Throwable) { atomic.failWrite(output); throw error }
                }.onFailure { rolledBack = false; failure.addSuppressed(it) }
            }
            if (rolledBack) created.asReversed().forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
            throw failure
        }
    }

    fun deleteVoice(context: Context, packId: String, voiceId: String): Unit = PocketEngine.updateModels {
        val pack = find(context, packId) ?: return@updateModels
        val voice = pack.voices.firstOrNull { it.id == voiceId } ?: return@updateModels
        require(voice.userCreated) { "Only user-added voices can be deleted" }
        val file = File(pack.voicesDir, voice.fileName).canonicalFile
        require(file.parentFile == pack.voicesDir.canonicalFile)
        val trash = File(pack.voicesDir, ".delete-${UUID.randomUUID()}")
        check(file.renameTo(trash))
        val updated = pack.copy(voices = pack.voices.filterNot { it.id == voiceId })
        try { writeManifest(updated) } catch (error: Throwable) { trash.renameTo(file); throw error }
        if (prefs(context).getString("voice.$packId", null) == voiceId) {
            prefs(context).edit().remove("voice.$packId").apply()
        }
        check(trash.delete()) { "Voice removed, but its temporary file could not be deleted" }
        val archive = recordingArchive(pack, voiceId)
        if (archive.exists()) check(archive.deleteRecursively()) { "Could not remove recording sources" }
    }


    fun voiceName(pack: ModelPack, voice: PackVoice): String = "${pack.id}::${voice.id}"

    fun voiceLocale(pack: ModelPack, voice: PackVoice): Locale {
        // Some TTS clients preserve only the locale and discard Voice.name.
        // Give every voice a stable, valid BCP-47 variant so those clients can
        // still address two voices of the same language independently.
        val variant = String.format(
            Locale.ROOT,
            "V%07X",
            "${pack.id}::${voice.id}".hashCode() and 0x0FFFFFFF
        )
        return Locale.Builder().setLocale(pack.locale).setVariant(variant).build()
    }

    fun findVoiceByName(context: Context, name: String?): Pair<ModelPack, PackVoice>? {
        if (name.isNullOrBlank() || "::" !in name) return null
        val (packId, voiceId) = name.split("::", limit = 2)
        val pack = list(context).firstOrNull { it.id == packId } ?: return null
        val voice = pack.voices.firstOrNull { it.id == voiceId } ?: return null
        return pack to voice
    }

    fun languageMatches(packLocale: Locale, requested: Locale): Boolean {
        if (requested.language.isBlank()) return true
        val requestedLanguage = requested.language.lowercase(Locale.ROOT)
        val aliases = buildSet {
            add(packLocale.language.lowercase(Locale.ROOT))
            runCatching { add(packLocale.isO3Language.lowercase(Locale.ROOT)) }
        }
        return requestedLanguage in aliases
    }

    fun findVoiceByLocale(context: Context, locale: Locale): Pair<ModelPack, PackVoice>? {
        if (locale.variant.isBlank()) return null
        list(context).filter { languageMatches(it.locale, locale) }.forEach { pack ->
            pack.voices.firstOrNull {
                voiceLocale(pack, it).variant.equals(locale.variant, true)
            }?.let { return pack to it }
        }
        return null
    }

    fun resolveVoice(context: Context, name: String?, locale: Locale? = null): Pair<ModelPack, PackVoice>? {
        val packs = list(context)
        findVoiceByName(context, name)?.let { return it }
        val matching = packs.filter { pack ->
            locale == null || languageMatches(pack.locale, locale)
        }
        if (locale != null) findVoiceByLocale(context, locale)?.let { return it }
        val candidates = if (matching.isNotEmpty()) matching else {
            packs.filter { it.locale.language.equals("en", true) }.ifEmpty { packs }
        }
        val pack = candidates.firstOrNull { it.id == selectedPack(context)?.id }
            ?: preferredSystemPack(candidates)
        val voice = pack?.voices?.firstOrNull { it.id == selectedVoiceId(context, pack) } ?: pack?.voices?.firstOrNull()
        return if (pack != null && voice != null) pack to voice else null
    }

    private fun readManifest(root: File): ModelPack {
        val json = JSONObject(File(root, "manifest.json").readText())
        require(json.optInt("format", 0) == FORMAT_VERSION) { "Nicht unterstütztes Paketformat" }
        val voiceArray = json.optJSONArray("voices") ?: JSONArray()
        val voices = (0 until voiceArray.length()).map { index ->
            val item = voiceArray.getJSONObject(index)
            val fileName = item.getString("file")
            require(fileName.isNotBlank() && fileName != "." && fileName != ".." && '/' !in fileName && '\\' !in fileName)
            val archive = File(root, "voices/.recordings/${item.getString("id")}")
            PackVoice(item.getString("id"), item.getString("name"), fileName, item.optBoolean("userCreated", false),
                item.optBoolean("audioEdited", File(archive, "enhanced.wav").isFile || File(archive, "trimmed.wav").isFile))
        }
        return ModelPack(
            id = sanitizeId(json.getString("id")),
            displayName = json.getString("name"),
            languageTag = json.getString("languageTag"),
            precision = json.optString("precision", "fp32").lowercase(Locale.ROOT),
            defaultTemperature = json.optDouble("temperature", 0.7).toFloat(),
            defaultLsdSteps = json.optInt("lsdSteps", 1),
            defaultThreads = json.optInt("threads", 2),
            voices = voices,
            root = root
        )
    }

    private fun writeManifest(pack: ModelPack, backupOrigins: Map<String, String> = emptyMap()) {
        pack.root.mkdirs()
        val previous = if (pack.manifestFile.isFile) JSONObject(pack.manifestFile.readText()) else JSONObject()
        val oldVoices = previous.optJSONArray("voices") ?: JSONArray()
        val voices = JSONArray().apply {
            pack.voices.forEach { voice ->
                val metadata = (0 until oldVoices.length()).map { oldVoices.getJSONObject(it) }.firstOrNull { it.optString("id") == voice.id } ?: JSONObject()
                backupOrigins[voice.id]?.let { metadata.put("backupSourceId", it) }
                put(metadata.put("id", voice.id).put("name", voice.displayName).put("file", voice.fileName).put("userCreated", voice.userCreated).put("audioEdited", voice.audioEdited))
            }
        }
        previous
            .put("format", FORMAT_VERSION)
            .put("id", pack.id)
            .put("name", pack.displayName)
            .put("languageTag", pack.languageTag)
            .put("precision", pack.precision)
            .put("temperature", pack.defaultTemperature.toDouble())
            .put("lsdSteps", pack.defaultLsdSteps)
            .put("threads", pack.defaultThreads)
            .put("voices", voices)
            .toString(2)
            .also { json ->
                val atomic = android.util.AtomicFile(pack.manifestFile)
                val output = atomic.startWrite()
                try { output.write(json.toByteArray()); atomic.finishWrite(output) }
                catch (error: Throwable) { atomic.failWrite(output); throw error }
            }
    }

    private fun preferredSystemPack(packs: List<ModelPack>): ModelPack? {
        val locales = LocaleList.getDefault()
        for (index in 0 until locales.size()) {
            val locale = locales[index]
            packs.firstOrNull { languageMatches(it.locale, locale) }?.let { return it }
        }
        return packs.firstOrNull { it.locale.language.equals("en", true) } ?: packs.firstOrNull()
    }

    private fun isValid(pack: ModelPack): Boolean {
        if (pack.precision !in setOf("fp32", "int8")) return false
        val suffix = if (pack.precision == "int8") "_int8" else ""
        val precisionModels = listOf("flow_lm_flow$suffix.onnx", "flow_lm_main$suffix.onnx", "mimi_decoder$suffix.onnx")
        return pack.id.isNotBlank() && pack.locale.language.isNotBlank() &&
            (sharedModels + precisionModels).all { File(pack.modelsDir, it).isFile } &&
            pack.voices.all { File(pack.voicesDir, it.fileName).isFile }
    }


    private fun copyVoiceLimited(input: InputStream, target: File, limitError: String) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copiedBytes = 0L
        FileOutputStream(target).use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                copiedBytes += read
                require(copiedBytes <= PcmWav.MAX_VOICE_BYTES) { limitError }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun openSelectedDocument(context: Context, uri: Uri, openErrorResource: Int): InputStream {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw SecurityException(context.getString(R.string.error_untrusted_document))
        }
        val authority = uri.authority
        if (authority.isNullOrBlank() || context.packageManager.resolveContentProvider(authority, 0) == null) {
            throw SecurityException(context.getString(R.string.error_untrusted_document))
        }
        if (
            context.checkUriPermission(
                uri,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(context.getString(R.string.error_untrusted_document))
        }
        val rawPath = uri.path ?: throw SecurityException(
            context.getString(R.string.error_untrusted_document)
        )
        val normalizedPath = FileSystems.getDefault().getPath(rawPath).normalize()
        if (
            normalizedPath.startsWith("/data") ||
                normalizedPath.startsWith("/proc") ||
                normalizedPath.startsWith("/sys") ||
                normalizedPath.startsWith("/dev")
        ) {
            throw SecurityException(context.getString(R.string.error_untrusted_document))
        }
        check(!ImportSecurity.isBlockedDocumentPath(normalizedPath))
        return requireNotNull(context.contentResolver.openInputStream(uri)) {
            context.getString(openErrorResource)
        }
    }

    private fun sanitizeId(raw: String): String {
        val id = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        require(id.isNotBlank() && !id.startsWith('.') && id == raw) { "Ungültige Paket-ID" }
        return id
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: context.getString(R.string.new_voice)
        }
        return uri.lastPathSegment ?: context.getString(R.string.new_voice)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val DEFAULT_SENTENCE_PAUSE_MS = 250
    private const val DEFAULT_MAX_TEXT_TOKENS = 50
}
