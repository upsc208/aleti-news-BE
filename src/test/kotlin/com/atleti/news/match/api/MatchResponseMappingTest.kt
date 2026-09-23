package com.atleti.news.match.api

import com.atleti.news.match.api.dto.toResponse
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.UpcomingMatch
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 엔티티 → 응답 매핑을 서비스에서 컨트롤러 쪽으로 옮기면서, 응답 내용이 그대로인지 고정한다.
 * 대회명 한국어 변환과 JSON 컬럼 해석이 이 매핑에 들어 있어 조용히 빠지면 화면에서만 티가 난다.
 */
class MatchResponseMappingTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `경기 결과를 응답으로 옮긴다`() {
        val response = matchResult().toResponse(mapper)

        assertThat(response.matchId).isEqualTo("4321")
        assertThat(response.homeScore).isEqualTo(2)
        assertThat(response.tournament).isEqualTo("라리가")
        assertThat(response.scorers?.map { it.name }).containsExactly("훌리안 알바레스")
        assertThat(response.lineup?.home?.formation).isEqualTo("4-4-2")
        assertThat(response.substitutions?.first()?.inName).isEqualTo("코케")
        assertThat(response.cards?.first()?.color).isEqualTo("yellow")
        assertThat(response.stats["possession"]?.home).isEqualTo("55")
    }

    @Test
    fun `JSON 컬럼이 깨져도 그 칸만 비우고 경기는 살린다`() {
        val response = matchResult(scorers = "{망가진 JSON", lineup = null).toResponse(mapper)

        assertThat(response.scorers).isNull()
        assertThat(response.lineup).isNull()
        assertThat(response.matchId).isEqualTo("4321")
        assertThat(response.homeTeam).isEqualTo("아틀레티코")
    }

    @Test
    fun `예정 경기도 대회명을 한국어로 바꾼다`() {
        val response = UpcomingMatch(
            matchId = "9999",
            opponent = "레알 마드리드",
            matchDate = "2026-09-27T21:00:00+02:00",
            isHome = false,
            tournament = "LaLiga",
        ).toResponse()

        assertThat(response.matchId).isEqualTo("9999")
        assertThat(response.opponent).isEqualTo("레알 마드리드")
        assertThat(response.tournament).isEqualTo("라리가")
    }

    private fun matchResult(
        scorers: String? = """[{"name":"훌리안 알바레스","time":"23'","assist":null,"isHome":true}]""",
        lineup: String? = """{"home":{"formation":"4-4-2","starters":[{"name":"얀 오블락","shirt":"13"}]},
                              "away":{"formation":"4-3-3","starters":[]}}""",
    ) = MatchResult(
        matchId = "4321",
        homeTeam = "아틀레티코",
        awayTeam = "오사수나",
        homeScore = 2,
        awayScore = 0,
        matchDate = "2026-09-20T21:00:00+02:00",
        tournament = "LaLiga",
        isHome = true,
        scorers = scorers,
        lineup = lineup,
        substitutions = """[{"time":"60'","outName":"파블로 바리오스","inName":"코케","isHome":true}]""",
        cards = """[{"name":"마르코스 요렌테","time":"70'","shirt":"14","color":"yellow","isHome":true}]""",
        xg = """{"possession":{"home":"55","away":"45"}}""",
    )
}
