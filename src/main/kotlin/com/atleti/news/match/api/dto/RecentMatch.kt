package com.atleti.news.match.api.dto

import com.atleti.news.match.domain.CardInfo
import com.atleti.news.match.domain.LineupInfo
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.ScorerInfo
import com.atleti.news.match.domain.StatValue
import com.atleti.news.match.domain.SubstitutionInfo
import com.atleti.news.match.external.TournamentNameKo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class RecentMatch(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String,
    val scorers: List<ScorerInfo>? = null,
    val lineup: LineupInfo? = null,
    val substitutions: List<SubstitutionInfo>? = null,
    val cards: List<CardInfo>? = null,
    val stats: Map<String, StatValue> = emptyMap()
)

// 대회명 한국어 변환과 JSON 컬럼 해석
fun MatchResult.toResponse(mapper: ObjectMapper) = RecentMatch(
    matchId = matchId,
    homeTeam = homeTeam,
    awayTeam = awayTeam,
    homeScore = homeScore,
    awayScore = awayScore,
    matchDate = matchDate,
    isHome = isHome,
    tournament = TournamentNameKo.toKorean(tournament),
    scorers = jsonOrNull<List<ScorerInfo>>(mapper, scorers),
    lineup = jsonOrNull<LineupInfo>(mapper, lineup),
    substitutions = jsonOrNull<List<SubstitutionInfo>>(mapper, substitutions),
    cards = jsonOrNull<List<CardInfo>>(mapper, cards),
    stats = jsonOrNull<Map<String, StatValue>>(mapper, xg) ?: emptyMap()
)

// 깨진 컬럼은 그 칸만 null 처리 — 경기 카드 전체를 잃지 않기 위함
private inline fun <reified T> jsonOrNull(mapper: ObjectMapper, raw: String?): T? =
    raw?.let { runCatching { mapper.readValue<T>(it) }.getOrNull() }
