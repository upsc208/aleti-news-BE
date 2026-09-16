package com.atleti.news.news.external

/**
 * 모델이 같은 말을 끝없이 되풀이한 꼬리를 잘라 낸다.
 */
object RepetitionGuard {
    private const val REPEAT_LIMIT = 15

    private const val MAX_UNIT_LENGTH = 40

    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '\n')

    data class Result(val text: String, val repeatedUnit: String?) {
        val looped: Boolean get() = repeatedUnit != null
    }

    /**
     * 반복 구간이 시작되는 자리에서 잘라 낸 결과를 돌려준다. 반복이 없으면 원문 그대로 번역.
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

                if (repeats >= REPEAT_LIMIT) {
                    return Result(text.substring(0, cutPoint(text, i, unitLength)).trimEnd(), text.substring(i, end))
                }
            }
            i++
        }
        return Result(text, null)
    }

    /**
     * 자를 자리를 문장 끝에 맞춘다.
     */
    private fun cutPoint(text: String, loopStart: Int, unitLength: Int): Int {
        val unitEnd = minOf(loopStart + unitLength, text.length)
        for (i in loopStart until unitEnd) {
            if (text[i] in SENTENCE_ENDINGS) return i + 1
        }
        return loopStart
    }
}
