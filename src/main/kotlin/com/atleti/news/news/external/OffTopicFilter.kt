package com.atleti.news.news.external

/**
 * 번역에서 축구와 상관없는 문단을 걷어낸다.
 */
object OffTopicFilter {
    /** 문단 구분 — 빈 줄 하나 */
    private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"

    /** 협찬·판매 문단 — 할인 코드, 추첨 응모, 구매 안내가 함께 나온다 */
    private val SPONSOR = Regex("할인 ?코드|프로모션 ?코드|쿠폰|응모|추첨에 참여|구매하면|웹사이트에서 제품")

    /** 축구가 아닌 종목 — 이 방송에서 실제로 나온 것들 */
    private val OTHER_SPORTS = Regex("F1|포뮬러|모터스포츠|테니스|배드민턴|페탕크|비둘기 경주|농구|야구")

    /** 축구가 아닌 화제 — 영화 홍보, 출연진 사담 */
    private val OFF_TOPIC = Regex("영화|애니메이션|드라마|넷플릭스|시사회|성우|개봉|앨범|콘서트|정치|선거|주식|코인")

    /** 축구 이야기라는 신호. 딴 화제와 함께 나오면 문단을 살린다 */
    private val FOOTBALL = Regex(
        "축구|아틀레티|리그|이적|골키퍼|득점|수비수|공격수|구단|감독|선수|경기|훈련|부상|" +
            "승리|패배|무승부|시즌|우승|라리가|챔피언스|유소년|계약|스쿼드|선발|교체|벤치"
    )

    data class Result(val text: String, val dropped: List<String>)

    /**
     * 다음 중 하나에 걸리면 문단을 지운다.
     * - 협찬·판매 문구가 있다
     * - 축구가 아닌 화제인데 축구 낱말이 함께 나오지 않는다
     * - 문단 꼬리표가 다른 종목이거나, 다른 종목이 두 번 이상 나온다
     */
    fun clean(text: String): Result {
        val dropped = mutableListOf<String>()
        val kept = text.split(PARAGRAPH_BREAK).filter { paragraph ->
            val label = paragraph.lineSequence().first()
            val offTopic = SPONSOR.containsMatchIn(paragraph) ||
                (OFF_TOPIC.containsMatchIn(paragraph) && !FOOTBALL.containsMatchIn(paragraph)) ||
                OTHER_SPORTS.containsMatchIn(label) ||
                OTHER_SPORTS.findAll(paragraph).count() >= 2
            if (offTopic) dropped += label.take(30)
            !offTopic
        }
        return Result(kept.joinToString(PARAGRAPH_BREAK).trim(), dropped)
    }
}
