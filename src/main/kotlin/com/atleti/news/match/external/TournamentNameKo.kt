package com.atleti.news.match.external

/**
 * FotMob 대회명(영문) → 한글 통용 표기.
 *
 * FotMob은 일정 노드에서 시즌 접미사 없는 순수 대회명을 준다 ("LaLiga", "Champions League").
 * 다만 다른 노드에는 "LaLiga 2025/2026" 형태도 있어, 접미사가 붙어 와도 벗겨내고 찾는다.
 *
 * 매핑에 없는 대회는 영문을 그대로 돌려준다. 새 대회가 생기거나 FotMob이 표기를 바꿔도
 * 화면이 빈칸이 되지는 않는다.
 *
 * DB에는 영문 원본을 저장하고 응답을 만들 때만 변환한다 — 표기를 바꿔도 재스크랩이 필요 없다.
 */
object TournamentNameKo {

    private val SEASON_SUFFIX = Regex("""\s+\d{4}(/\d{2,4})?$""")

    private val MAP = mapOf(
        "laliga" to "라리가",
        "la liga" to "라리가",
        "champions league" to "챔피언스리그",
        "uefa champions league" to "챔피언스리그",
        "europa league" to "유로파리그",
        "uefa europa league" to "유로파리그",
        "conference league" to "컨퍼런스리그",
        "uefa conference league" to "컨퍼런스리그",
        "uefa europa conference league" to "컨퍼런스리그",
        "copa del rey" to "코파 델 레이",
        "supercopa de españa" to "수페르코파",
        "spanish super cup" to "수페르코파",
        "club friendlies" to "친선전",
        "friendlies" to "친선전",
        "club friendly" to "친선전",
        "fifa club world cup" to "클럽 월드컵",
        "club world cup" to "클럽 월드컵"
    )

    fun toKorean(raw: String): String {
        val key = raw.trim().replace(SEASON_SUFFIX, "").lowercase()
        return MAP[key] ?: raw.trim()
    }
}
