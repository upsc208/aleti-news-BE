package com.atleti.news.match.api.dto

import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.external.TournamentNameKo

data class UpcomingMatchResponse(
    // 채팅방 키 — 스크랩에서 못 얻으면 null
    val matchId: String?,
    val opponent: String,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String
)

fun UpcomingMatch.toResponse() = UpcomingMatchResponse(
    matchId = matchId,
    opponent = opponent,
    matchDate = matchDate,
    isHome = isHome,
    tournament = TournamentNameKo.toKorean(tournament)
)
