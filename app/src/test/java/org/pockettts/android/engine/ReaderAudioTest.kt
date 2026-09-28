package org.pockettts.android.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ReaderAudioTest {
    @Test fun wavExportContainsAllPcmAndCorrectHeader() {
        val directory = Files.createTempDirectory("reader-wav-test").toFile()
        try {
            val first = directory.resolve("one.wav")
            val second = directory.resolve("two.wav")
            PcmWav.Writer(first).use { it.write(floatArrayOf(-1f, 0f, 1f)) }
            PcmWav.Writer(second).use { it.write(byteArrayOf(1, 0, 2, 0), 4) }
            assertTrue(PcmWav.isVoiceSample(first))
            val bytes = ByteArrayOutputStream().also { PcmWav.concatenate(listOf(first, second), it) }.toByteArray()
            assertEquals(54, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(46, buffer.getInt(4)); assertEquals(24000, buffer.getInt(24))
            assertEquals(1, buffer.getShort(20).toInt()); assertEquals(16, buffer.getShort(34).toInt())
            assertEquals(10, buffer.getInt(40))
            assertArrayEquals(byteArrayOf(1, -128, 0, 0, -1, 127, 1, 0, 2, 0), bytes.copyOfRange(44, 54))
        } finally { directory.deleteRecursively() }
    }
    @Test fun seeksAcrossBoundariesAndClampsToGeneratedAudio() {
        val timeline = AudioTimeline()
        timeline.append(1000); timeline.append(2500)
        assertEquals(0 to 0L, timeline.locate(-10))
        assertEquals(0 to 999L, timeline.locate(999))
        assertEquals(1 to 0L, timeline.locate(1000))
        assertEquals(1 to 2499L, timeline.locate(9000))
        assertEquals(3500, timeline.duration)
        timeline.append(1000)
        assertEquals(2 to 0L, timeline.locate(3500))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRiffOverflow() { PcmWav.header(0x1_0000_0000L) }
    @Test fun rejectsEmptyAndTruncatedVoiceSamples() {
        val file = Files.createTempFile("reader-invalid", ".wav").toFile()
        try {
            PcmWav.Writer(file).close()
            assertFalse(PcmWav.isVoiceSample(file))
            file.writeBytes(PcmWav.header(48_000))
            assertFalse(PcmWav.isVoiceSample(file))
        } finally { file.delete() }
    }
}
