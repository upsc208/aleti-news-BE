package com.atleti.news.news.external

/**
 * 번역에서 광고 문단과 축구가 아닌 종목 이야기를 걷어낸다.
 *
 */
object OffTopicFilter {
    /** 협찬·판매 문단 — 할인 코드, 추첨 응모, 구매 안내가 함께 나온다 */
    private val SPONSOR = Regex("할인 ?코드|프로모션 ?코드|쿠폰|응모|추첨에 참여|구매하면|웹사이트에서 제품")

    /** 축구가 아닌 종목 — 이 방송에서 실제로 나온 것들 */
    private val OTHER_SPORTS = Regex("F1|포뮬러|모터스포츠|테니스|배드민턴|페탕크|비둘기 경주|농구|야구")

    data class Result(val text: String, val dropped: List<String>)

    /** 문단 구분 — 빈 줄 하나 */
    private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"

    /**
     * 문단 단위로만 지운다. 축구 얘기 도중 다른 종목이 한 번 스친 것까지 지우지 않으려고 두 가지만 본다.
     */
    fun clean(text: String): Result {
        val dropped = mutableListOf<String>()
        val kept = text.split(PARAGRAPH_BREAK).filter { paragraph ->
            val label = paragraph.lineSequence().first()
            val offTopic = SPONSOR.containsMatchIn(paragraph) ||
                OTHER_SPORTS.containsMatchIn(label) ||
                OTHER_SPORTS.findAll(paragraph).count() >= 2
            if (offTopic) dropped += label.take(30)
            !offTopic
        }
        return Result(kept.joinToString(PARAGRAPH_BREAK).trim(), dropped)
    }
}