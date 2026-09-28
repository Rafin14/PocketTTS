package org.pockettts.android.engine

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One model instance per process, shared by Reader and Android TTS. All native lifetimes are serialized. */
internal object PocketEngine {
    // ponytail: serialize model access to bound RAM; separate instances only if concurrent inference becomes necessary.
    private var engine: NativePocketTts? = null
    private var key: List<Any>? = null
    private val reaper = Executors.newSingleThreadScheduledExecutor()
    private var release: ScheduledFuture<*>? = null
    private var revision = 0L

    @Synchronized fun <T> withEngine(context: Context, pack: ModelPack, block: (NativePocketTts) -> T): T {
        release?.cancel(false)
        val current = ++revision
        val next = listOf(pack.root.absolutePath, pack.manifestFile.lastModified(), pack.precision,
            ModelPackRepository.temperature(context, pack), ModelPackRepository.lsdSteps(context, pack),
            ModelPackRepository.threads(context, pack), ModelPackRepository.sentencePauseMs(context, pack),
            ModelPackRepository.maxTextTokens(context, pack))
        try {
            if (engine == null || next != key) {
                engine?.close(); engine = null; key = null
                engine = NativePocketTts(pack.modelsDir.absolutePath, pack.voicesDir.absolutePath, pack.precision,
                    next[3] as Float, next[4] as Int, next[5] as Int, next[6] as Int, next[7] as Int)
                key = next
            }
            return block(requireNotNull(engine))
        } finally {
            release = reaper.schedule({ synchronized(this) {
                if (revision == current) { engine?.close(); engine = null; key = null }
            } }, 60, TimeUnit.SECONDS)
        }
    }

    /** Import/delete cannot race a native voice read. Caller must run on a worker. */
    @Synchronized fun <T> updateModels(block: () -> T): T {
        release?.cancel(false)
        revision++
        engine?.close(); engine = null; key = null
        return block()
    }
}
