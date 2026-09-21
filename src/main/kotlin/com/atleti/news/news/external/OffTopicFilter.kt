package com.atleti.news.news.external

/* 주제 판단은 번역할 때 모델이 [축구]/[비축구] 태그로 하고 여기서는 그 태그만 체킹 */
object OffTopicFilter {
    private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"

    const val FOOTBALL_LABEL = "[축구]"
    const val OFF_TOPIC_LABEL = "[비축구]"

    private val SPONSOR = Regex("할인 ?코드|프로모션 ?코드|쿠폰|구매하면|웹사이트에서 제품|구매 링크")

    data class Result(val text: String, val dropped: List<String>)

    fun clean(text: String): Result {
        val dropped = mutableListOf<String>()
        val kept = text.split(PARAGRAPH_BREAK).mapNotNull { paragraph ->
            val trimmed = paragraph.trimStart()
            val drop = trimmed.startsWith(OFF_TOPIC_LABEL) || SPONSOR.containsMatchIn(paragraph)
            if (drop) {
                dropped += paragraph.lineSequence().first().take(30)
                null
            } else {
                trimmed.removePrefix(FOOTBALL_LABEL)
            }
        }
        return Result(kept.joinToString(PARAGRAPH_BREAK).trim(), dropped)
    }
}
