package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class VoiceSampleLimitsTest {
    @Test fun exactSizeDurationBoundariesAndOverflowSafeValidation() {
        val file = File.createTempFile("voice-limits-", ".wav")
        fun wav(bytes: Long, rate: Int, channels: Int = 1) {
            RandomAccessFile(file, "rw").use { it.setLength(bytes + 44); it.seek(0); it.write(PcmWav.header(bytes, rate, channels)) }
        }
        try {
            assertEquals(256L * 1024 * 1024, PcmWav.MAX_VOICE_BYTES)
            assertEquals(1_800_000L, PcmWav.MAX_SOURCE_MS)
            wav(PcmWav.MAX_VOICE_BYTES - 44, 48000, 2)
            PcmWav.validateVoiceSample(file)
            wav(PcmWav.MAX_VOICE_BYTES - 40, 48000, 2)
            assertEquals(PcmWav.SIZE_ERROR, runCatching { PcmWav.validateVoiceSample(file) }.exceptionOrNull()?.message)
            wav(24000L * 2 * 1800, 24000)
            PcmWav.validateVoiceSample(file)
            wav(24000L * 2 * 1800 + 2, 24000)
            assertEquals(PcmWav.DURATION_ERROR, runCatching { PcmWav.validateVoiceSample(file) }.exceptionOrNull()?.message)
            // A forged unsigned RIFF length must fail without allocating that length.
            RandomAccessFile(file, "rw").use { it.setLength(44); it.seek(4); it.writeInt(-1) }
            assertFalse(PcmWav.isVoiceSample(file))
        } finally { file.delete() }
    }
}
