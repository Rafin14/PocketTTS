package org.pockettts.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextChunkerTest {
    @Test fun emptyAndLargeDocumentsPreserveContent() {
        assertTrue(ReaderTextChunker.chunk(" \n\t", 32).isEmpty())
        val text = "Hello reader, this is a sentence.\n\n".repeat(10000)
        val chunks = ReaderTextChunker.chunk(text, 120)
        assertTrue(chunks.all { it.length <= 120 })
        fun normalized(value: String) = value.filterNot { it.isWhitespace() }
        assertEquals(normalized(text), normalized(chunks.joinToString(" ")))
    }
    @Test fun prioritizesClausesAndDoesNotBreakUnicodePairs() {
        assertEquals("A useful clause,", ReaderTextChunker.chunk("A useful clause, then several more words without punctuation to end.", 32).first())
        val text = "a".repeat(31) + "😀" + "b".repeat(50)
        val chunks = ReaderTextChunker.chunk(text, 32)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
    @Test fun preservesParagraphsWhenTheyFit() {
        assertEquals(listOf("First paragraph.\n\nSecond paragraph."), ReaderTextChunker.chunk("First paragraph.\n\nSecond paragraph.", 80))
    }

    @Test fun keepsEveryChunkWithinTheRequestLimit() {
        val chunks = ReaderTextChunker.chunk("One sentence is here. Another sentence follows, with a clause. Final sentence.", 32)
        assertTrue(chunks.all { it.length <= 32 })
        assertTrue(chunks.joinToString(" ").contains("Final sentence"))
    }
}
