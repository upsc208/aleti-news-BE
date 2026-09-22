package com.atleti.news.news.external

/* 모델이 같은 말을 되풀이한 부분을 걷어냄.*/
object RepetitionGuard {
    private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"
    private const val MAX_UNIT_LENGTH = 200
    private const val LOOP_RUN = 3

    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '\n')
    private val WHITESPACE = Regex("""\s""")
    private val LABEL = Regex("""^\[[^\]]*\]""")
    private val LABEL_NUMBER = Regex("""[0-9]""")

    data class Result(val text: String, val repeatedUnit: String?) {
        val looped: Boolean get() = repeatedUnit != null
    }

    // 같은 조각이 연달아 되풀이되면 그 자리에서 잘라 냄
    fun trimLoop(text: String): Result {
        var i = 0
        while (i < text.length) {
            for (unitLength in 1..MAX_UNIT_LENGTH) {
                val end = i + unitLength
                if (end > text.length) break

                var repeats = 1
                var next = end
                while (next + unitLength <= text.length &&
                    text.regionMatches(next, text, i, unitLength)
                ) {
                    repeats++
                    next += unitLength
                }

                if (repeats >= repeatLimitFor(unitLength)) {
                    return Result(text.substring(0, cutPoint(text, i, unitLength)).trimEnd(), text.substring(i, end))
                }
            }
            i++
        }
        return Result(text, null)
    }

    fun dropRepeatedParagraphs(text: String): Result {
        val seen = HashSet<String>()
        val kept = mutableListOf<String>()
        var loopedBody: String? = null
        var previousKey: String? = null
        var run = 1

        for (paragraph in text.split(PARAGRAPH_BREAK)) {
            val key = comparisonKey(paragraph)
            if (key.isEmpty()) {
                kept += paragraph
                continue
            }

            run = if (key == previousKey) run + 1 else 1
            previousKey = key
            if (run >= LOOP_RUN && loopedBody == null) loopedBody = paragraph.take(40)

            if (seen.add(key)) kept += paragraph
        }

        return Result(kept.joinToString(PARAGRAPH_BREAK).trim(), loopedBody)
    }

    private fun repeatLimitFor(unitLength: Int): Int = when {
        unitLength <= 10 -> 15
        unitLength <= 60 -> 6
        else -> 4
    }

    // 문장 중간에서 끊기지 않게 자를 자리를 문장 끝에 맞추게 함
    private fun cutPoint(text: String, loopStart: Int, unitLength: Int): Int {
        val unitEnd = minOf(loopStart + unitLength, text.length)
        for (i in loopStart until unitEnd) {
            if (text[i] in SENTENCE_ENDINGS) return i + 1
        }
        return loopStart
    }

    // 라벨의 일련번호만 삭제
    private fun comparisonKey(paragraph: String): String {
        val trimmed = paragraph.trim()
        val label = LABEL.find(trimmed)?.value.orEmpty()
        val body = trimmed.removePrefix(label)
        return LABEL_NUMBER.replace(label, "") + body.replace(WHITESPACE, "")
    }
}
