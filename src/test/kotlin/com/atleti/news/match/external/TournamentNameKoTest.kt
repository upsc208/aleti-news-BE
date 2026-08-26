package com.atleti.news.match.external

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TournamentNameKoTest {

    @Test
    fun `FotMob이 실제로 내려주는 대회명을 한글로 바꾼다`() {
        // 일정 노드에서 확인한 실제 값 (leagueId 87 / 42 / 489)
        assertEquals("라리가", TournamentNameKo.toKorean("LaLiga"))
        assertEquals("챔피언스리그", TournamentNameKo.toKorean("Champions League"))
        assertEquals("친선전", TournamentNameKo.toKorean("Club Friendlies"))

        // 아틀레티코 이력에 있는 나머지 대회
        assertEquals("코파 델 레이", TournamentNameKo.toKorean("Copa del Rey"))
        assertEquals("수페르코파", TournamentNameKo.toKorean("Supercopa de España"))
        assertEquals("유로파리그", TournamentNameKo.toKorean("Europa League"))
        assertEquals("클럽 월드컵", TournamentNameKo.toKorean("FIFA Club World Cup"))
    }

    @Test
    fun `시즌 접미사가 붙어 와도 벗겨내고 찾는다`() {
        assertEquals("라리가", TournamentNameKo.toKorean("LaLiga 2025/2026"))
        assertEquals("코파 델 레이", TournamentNameKo.toKorean("Copa del Rey 2024/2025"))
        assertEquals("클럽 월드컵", TournamentNameKo.toKorean("FIFA Club World Cup 2025"))
    }

    @Test
    fun `표기가 흔들려도 같은 결과를 낸다`() {
        assertEquals("라리가", TournamentNameKo.toKorean("La Liga"))
        assertEquals("챔피언스리그", TournamentNameKo.toKorean("UEFA Champions League"))
        assertEquals("라리가", TournamentNameKo.toKorean("  laliga  "))
    }

    @Test
    fun `모르는 대회는 원문을 그대로 돌려준다`() {
        // 새 대회가 생기거나 FotMob이 표기를 바꿔도 화면이 빈칸이 되면 안 된다
        assertEquals("Intercontinental Cup", TournamentNameKo.toKorean("Intercontinental Cup"))
        assertEquals("Tournament", TournamentNameKo.toKorean("Tournament"))
    }
}
