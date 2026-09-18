package com.atleti.news.news.external

/**
 * 축구 이야기만 남긴다.
 *
 * 버릴 낱말을 나열하면 끝이 없고(영화·F1·정치·주식…), 남길 낱말만 나열해도 "F1 경기"와
 * "오사수나와 경기"를 못 가른다. 그래서 주제마다 모델이 [축구]/[비축구] 표시를 달게 하고,
 * 여기서는 그 표시와 판매 문구만 본다.
 */
object OffTopicFilter {
    /** 문단 구분 — 빈 줄 하나 */
    private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"

    /** 프롬프트가 모든 주제 라벨 앞에 붙이게 한 표시 */
    const val FOOTBALL_LABEL = "[축구]"
    const val OFF_TOPIC_LABEL = "[비축구]"

    /** 축구 이야기에 묻어 들어오는 판매 문구 */
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
