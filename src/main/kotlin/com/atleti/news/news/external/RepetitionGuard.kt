package com.atleti.news.news.external

/**
 * 모델이 같은 말을 되풀이한 부분을 걷어낸다.
 */
object RepetitionGuard {
    /** 문단 구분 — 빈 줄 하나 */
    private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"

    /** 되풀이되는 조각의 최대 길이. 짧은 낱말부터 한 문장까지 본다 */
    private const val MAX_UNIT_LENGTH = 200

    /** 같은 문단이 이만큼 연달아 나오면 모델이 갇힌 것으로 본다 */
    private const val LOOP_RUN = 3

    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '\n')

    private val WHITESPACE = Regex("""\s""")

    private val LABEL = Regex("""^\[[^\]]*\]""")

    /** 라벨의 일련번호만 지운다. 본문 숫자까지 지우면 "3경기"와 "5경기"가 같은 문단이 된다 */
    private val LABEL_NUMBER = Regex("""[0-9]""")

    data class Result(val text: String, val repeatedUnit: String?) {
        val looped: Boolean get() = repeatedUnit != null
    }

    /**
     * 같은 조각이 연달아 되풀이되면 그 자리에서 잘라 낸다. 반복이 없으면 원문 그대로.
     */
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

    /**
     * 같은 내용의 문단을 하나만 남긴다.
     */
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

    private fun cutPoint(text: String, loopStart: Int, unitLength: Int): Int {
        val unitEnd = minOf(loopStart + unitLength, text.length)
        for (i in loopStart until unitEnd) {
            if (text[i] in SENTENCE_ENDINGS) return i + 1
        }
        return loopStart
    }

    private fun comparisonKey(paragraph: String): String {
        val trimmed = paragraph.trim()
        val label = LABEL.find(trimmed)?.value.orEmpty()
        val body = trimmed.removePrefix(label)
        return LABEL_NUMBER.replace(label, "") + body.replace(WHITESPACE, "")
    }
}
