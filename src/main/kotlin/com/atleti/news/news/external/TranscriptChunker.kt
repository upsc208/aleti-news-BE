package com.atleti.news.news.external

/* 유튜브 긴 자막을 번역 요청 단위로 커팅. */
object TranscriptChunker {
    const val DEFAULT_MAX_CHARS = 10_000

    private val THEME_SONG_CHORUS = Regex("nuestra pasi[oó]n", RegexOption.IGNORE_CASE)
    private const val THEME_SONG_WINDOW = 5_000

    fun stripThemeSong(transcript: String): String {
        val half = transcript.length / 2
        var start = 0
        THEME_SONG_CHORUS.findAll(transcript.take(minOf(THEME_SONG_WINDOW, half))).lastOrNull()?.let { last ->
            val chorusEnd = transcript.indexOf('.', last.range.last)
            val nextLineEnd = if (chorusEnd == -1) -1 else transcript.indexOf('.', chorusEnd + 1)
            start = when {
                nextLineEnd != -1 && nextLineEnd < half -> nextLineEnd + 1
                chorusEnd != -1 -> chorusEnd + 1
                else -> last.range.last + 1
            }
        }

        var end = transcript.length
        val tailOffset = maxOf(start, half, transcript.length - THEME_SONG_WINDOW)
        THEME_SONG_CHORUS.find(transcript, tailOffset)?.let { first ->
            val chorusSentence = transcript.lastIndexOf('.', first.range.first)
            val previousSentence = if (chorusSentence <= start) -1 else transcript.lastIndexOf('.', chorusSentence - 1)
            end = when {
                previousSentence > start -> previousSentence + 1
                chorusSentence > start -> chorusSentence + 1
                else -> first.range.first
            }
        }

        return if (start >= end) transcript else transcript.substring(start, end).trim()
    }

    fun split(transcript: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val words = transcript.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
