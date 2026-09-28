package org.pockettts.android.engine

/** Bounded requests, preferring paragraphs, sentences, clauses, then words. */
internal object ReaderTextChunker {
    fun chunk(document: String, maxCharacters: Int): List<String> = chunks(document, maxCharacters).toList()

    data class Range(val start: Int, val end: Int)
    fun chunks(document: String, maxCharacters: Int): Sequence<String> = ranges(document, maxCharacters).map { document.substring(it.start, it.end) }
    fun ranges(document: String, maxCharacters: Int): Sequence<Range> = sequence {
        require(maxCharacters >= 32)
        var start = 0
        while (start < document.length) {
            while (start < document.length && document[start].isWhitespace()) start++
            if (start == document.length) break
            var end = minOf(start + maxCharacters, document.length)
            if (end < document.length) {
                val window = document.substring(start, end)
                // ponytail: punctuation heuristic, language-specific sentence segmentation if abbreviations prove troublesome.
                val boundary = listOf(PARAGRAPH, SENTENCE, CLAUSE, WORD)
                    .firstNotNullOfOrNull { pattern -> pattern.findAll(window).lastOrNull()?.range?.last?.plus(1) }
                if (boundary != null) end = start + boundary
                else if (Character.isHighSurrogate(document[end - 1]) && Character.isLowSurrogate(document[end])) end--
            }
            var spokenEnd = end
            while (spokenEnd > start && document[spokenEnd - 1].isWhitespace()) spokenEnd--
            if (spokenEnd > start) yield(Range(start, spokenEnd))
            start = end
        }
    }
    private val PARAGRAPH = Regex("\\r?\\n[\\t ]*\\r?\\n")
    private val SENTENCE = Regex("[.!?…][\\\"'”’)]*\\s+")
    private val CLAUSE = Regex("[,;:—]\\s+")
    private val WORD = Regex("\\s+")
}
