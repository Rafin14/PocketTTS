package org.pockettts.android.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class PocketTtsService : TextToSpeechService() {
    private val generation = AtomicLong()
    @Volatile private var loadedVoiceName: String? = null

    override fun onCreate() {
        super.onCreate()
        // Binder/synthesis worker entry points prepare the bundled files through the repository.
        // Never extract hundreds of MB on the service main thread.
    }

    override fun onGetLanguage(): Array<String> {
        val locale = ModelPackRepository.selectedPack(this)?.locale ?: Locale.US
        return arrayOf(locale.language, locale.country, locale.variant)
    }

    override fun onIsLanguageAvailable(lang: String, country: String?, variant: String?): Int {
        val requested = Locale(lang, country.orEmpty(), variant.orEmpty())
        val matches = ModelPackRepository.list(this).filter {
            ModelPackRepository.languageMatches(it.locale, requested)
        }
        if (matches.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE
        return if (matches.any { it.locale.country.equals(country, true) }) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): MutableList<Voice> = ModelPackRepository.list(this).flatMap { pack ->
        pack.voices.map { voice ->
            Voice(
                ModelPackRepository.voiceName(pack, voice),
                ModelPackRepository.voiceLocale(pack, voice),
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                emptySet()
            )
        }
    }.toMutableList()

    override fun onGetDefaultVoiceNameFor(lang: String, country: String?, variant: String?): String? {
        if (onIsLanguageAvailable(lang, country, variant) < TextToSpeech.LANG_AVAILABLE) return null
        val locale = Locale(lang, country.orEmpty(), variant.orEmpty())
        val resolved = ModelPackRepository.resolveVoice(this, null, locale) ?: return null
        return ModelPackRepository.voiceName(resolved.first, resolved.second)
    }

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (ModelPackRepository.findVoiceByName(this, voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String): Int {
        val resolved = ModelPackRepository.findVoiceByName(this, voiceName) ?: return TextToSpeech.ERROR
        loadedVoiceName = voiceName
        Log.i(TAG, "VOICE_LOADED pack=${resolved.first.id} voice=${resolved.second.id} name=$voiceName")
        return TextToSpeech.SUCCESS
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val requestGeneration = generation.get()
        run {
            val text = request.charSequenceText.toString()
            val locale = Locale(request.language.orEmpty(), request.country.orEmpty(), request.variant.orEmpty())
            val explicit = ModelPackRepository.findVoiceByName(this, request.voiceName)
            val localeVoice = ModelPackRepository.findVoiceByLocale(this, locale)
            val loaded = ModelPackRepository.findVoiceByName(this, loadedVoiceName)?.takeIf {
                ModelPackRepository.languageMatches(it.first.locale, locale)
            }
            val resolved = explicit ?: localeVoice ?: loaded ?: ModelPackRepository.resolveVoice(this, request.voiceName, locale)
            if (resolved == null) {
                Log.e(TAG, "SYNTH_FAILED no installed voice for ${locale.toLanguageTag()}")
                callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
                return@run
            }
            val (pack, voice) = resolved
            Log.i(
                TAG,
                "SYNTH_START pack=${pack.id} voice=${voice.id} requestVoice=${request.voiceName} " +
                    "loadedVoice=$loadedVoiceName locale=${request.language}-${request.country}-${request.variant} " +
                    "chars=${text.length} maxBuffer=${callback.maxBufferSize}"
            )
            if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                Log.e(TAG, "SYNTH_START_REJECTED")
                callback.error(TextToSpeech.ERROR_OUTPUT)
                return@run
            }
            var totalSamples = 0L
            var androidBlocks = 0
            val maxBlockBytes = callback.maxBufferSize.coerceAtLeast(2).let { it - (it % 2) }
            val ok = runCatching { PocketEngine.withEngine(this, pack) { tts ->
                if (generation.get() != requestGeneration) return@withEngine false
                tts.synthesize(text, voice.fileName, object : NativePocketTts.AudioSink {
                override fun onAudio(samples: FloatArray): Boolean {
                    if (generation.get() != requestGeneration) return false
                    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
                    val bytes = pcm.array()
                    var offset = 0
                    while (offset < bytes.size) {
                        val length = min(maxBlockBytes, bytes.size - offset)
                        if (callback.audioAvailable(bytes, offset, length) != TextToSpeech.SUCCESS) {
                            Log.e(TAG, "SYNTH_AUDIO_REJECTED block=$androidBlocks offset=$offset length=$length")
                            return false
                        }
                        offset += length
                        androidBlocks++
                    }
                    totalSamples += samples.size
                    Log.i(TAG, "SYNTH_AUDIO samples=${samples.size} total=$totalSamples blocks=$androidBlocks")
                    return true
                }
                })
            } }.getOrElse { Log.e(TAG, "SYNTH_FAILED", it); false }
            if (ok && totalSamples > 0) {
                val done = callback.done()
                Log.i(TAG, "SYNTH_DONE result=$done samples=$totalSamples blocks=$androidBlocks")
            } else {
                Log.e(TAG, "SYNTH_FAILED samples=$totalSamples blocks=$androidBlocks")
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
            }
        }
    }

    override fun onStop() { generation.incrementAndGet() }

    override fun onDestroy() {
        generation.incrementAndGet()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PocketTTS"
        const val SAMPLE_RATE = 24_000
    }
}
