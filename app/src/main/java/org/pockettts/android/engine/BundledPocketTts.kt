package org.pockettts.android.engine

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Worker-thread bootstrap shared by Reader and system TTS. No downloader or second engine. */
internal object BundledPocketTts {
    const val ID = "english-fp32"
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        val root = File(ModelPackRepository.packsRoot(context), ID).apply { mkdirs() }
        val index = context.assets.open("pockettts/files.tsv").bufferedReader().use { it.readText() }
        val marker = File(root, ".bundled-files")
        val previouslyVerified = marker.isFile && marker.readText() == index
        index.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val (path, length, hash) = line.split('\t')
            val target = File(root, path)
            // The installed manifest owns custom voices. Never replace it during app updates.
            if (path == "manifest.json" && target.isFile) return@forEach
            if (target.isFile && target.length() == length.toLong() &&
                (previouslyVerified || target.inputStream().use { digest(it) } == hash)) return@forEach
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                val actual = context.assets.open("pockettts/$path").use { input ->
                    val checksum = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); checksum.update(buffer, 0, count); copied += count
                    }
                    check(copied == length.toLong()) { "Incomplete bundled model: $path" }
                    checksum.digest().joinToString("") { "%02x".format(it) }
                }
                check(actual == hash) { "Bundled model checksum mismatch: $path" }
                atomic.finishWrite(output)
            } catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        val atomic = AtomicFile(marker)
        val output = atomic.startWrite()
        try { output.write(index.toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        ready = true
    }

    private fun digest(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
