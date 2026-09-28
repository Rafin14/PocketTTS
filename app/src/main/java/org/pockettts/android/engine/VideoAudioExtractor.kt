package org.pockettts.android.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

/** Platform decoders only. Keep source rate/channels; the shared trim pipeline normalizes them. */
internal object VideoAudioExtractor {
    private const val MAX_BYTES = PcmWav.MAX_VOICE_BYTES - 44
    data class Info(val durationMs: Int, val audioMime: String, val aspectRatio: Float)

    private fun open(context: Context, uri: Uri, cancel: CancellationSignal): MediaExtractor {
        cancel.throwIfCanceled()
        val extractor = MediaExtractor()
        try {
            requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r", cancel)).use {
                if (it.declaredLength < 0) extractor.setDataSource(it.fileDescriptor)
                else extractor.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
            }
            return extractor
        } catch (error: Exception) { extractor.release(); throw error }
    }

    private fun track(extractor: MediaExtractor): Int {
        require((0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) { "This file has no supported video track." }
        return (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("This video has no audio track. Choose a video with speech.")
    }

    fun inspect(context: Context, uri: Uri, cancel: CancellationSignal): Info {
        val extractor = open(context, uri, cancel)
        try {
            val format = extractor.getTrackFormat(track(extractor))
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            require(duration <= PcmWav.MAX_SOURCE_MS) { PcmWav.DURATION_ERROR }
            val video = (0 until extractor.trackCount).map(extractor::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val width = if (video.containsKey(MediaFormat.KEY_WIDTH)) video.getInteger(MediaFormat.KEY_WIDTH) else 16
            val height = if (video.containsKey(MediaFormat.KEY_HEIGHT)) video.getInteger(MediaFormat.KEY_HEIGHT) else 9
            val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION)) video.getInteger(MediaFormat.KEY_ROTATION) else 0
            val ratio = width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
            return Info(duration.toInt(), requireNotNull(format.getString(MediaFormat.KEY_MIME)), if (rotation % 180 != 0) 1f / ratio else ratio)
        } finally { extractor.release() }
    }

    fun extract(context: Context, uri: Uri, target: File, cancel: CancellationSignal) {
        val extractor = open(context, uri, cancel)
        var codec: MediaCodec? = null
        var output: PcmWav.Writer? = null
        var success = false
        try {
            val index = track(extractor)
            val inputFormat = extractor.getTrackFormat(index)
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) require(inputFormat.getLong(MediaFormat.KEY_DURATION) <= PcmWav.MAX_SOURCE_MS * 1000) { PcmWav.DURATION_ERROR }
            extractor.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(inputFormat, null, null, 0); decoder.start()
            var inputDone = false
            var rate = 0; var channels = 0
            var lastProgress = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            while (true) {
                cancel.throwIfCanceled()
                check(SystemClock.elapsedRealtime() - lastProgress < 15_000) { "The audio decoder stopped responding. Try another video." }
                if (!inputDone) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(slot))
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected audio cannot be extracted." }
                            decoder.queueInputBuffer(slot, 0, count, extractor.sampleTime, 0); extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val slot = decoder.dequeueOutputBuffer(info, 10_000)
                if (slot >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val format = decoder.getOutputFormat(slot)
                            val nextRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val nextChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(nextRate in 8000..192000 && nextChannels in 1..8) { "Unsupported audio sample rate or channel count." }
                            if (output == null) { rate = nextRate; channels = nextChannels; output = PcmWav.Writer(target, rate, channels) }
                            require(rate == nextRate && channels == nextChannels) { "Audio format changes mid-video. Choose another file." }
                            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val buffer = requireNotNull(decoder.getOutputBuffer(slot)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val writer = requireNotNull(output)
                            val bytes = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> info.size
                                AudioFormat.ENCODING_PCM_FLOAT -> info.size / 2
                                else -> error("The device decoder returned an unsupported PCM format.")
                            }
                            require(info.size % (channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2) == 0) { "Malformed decoded audio." }
                            require(writer.bytes + bytes <= minOf(MAX_BYTES, rate.toLong() * channels * 2 * PcmWav.MAX_SOURCE_SECONDS)) { "Extracted audio exceeds the 256 MB / 30 minute limit." }
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val samples = FloatArray(info.size / 4) { buffer.float }
                                require(samples.all { it.isFinite() }) { "Malformed decoded audio." }
                                writer.write(samples)
                            } else writer.write(ByteArray(info.size).also(buffer::get), info.size)
                        }
                    } finally { decoder.releaseOutputBuffer(slot, false) }
                    lastProgress = SystemClock.elapsedRealtime()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            require((output?.bytes ?: 0) >= rate.toLong() * channels * 2 * 3 && rate > 0) { "The video needs at least 3 seconds of audio." }
            output?.close(); output = null
            cancel.throwIfCanceled()
            success = true
        } finally {
            runCatching { output?.close() }; runCatching { codec?.release() }; extractor.release()
            if (!success) target.delete()
        }
    }
}
