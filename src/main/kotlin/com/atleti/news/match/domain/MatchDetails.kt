package com.atleti.news.match.domain

/* 경기 상세를 DB에 JSON 문자열로 넣고 꺼낼 때의 모양 */
data class StatValue(val home: String, val away: String)
data class ScorerInfo(val name: String, val time: String, val assist: String?, val isHome: Boolean)
data class SubstitutionInfo(val time: String, val outName: String, val inName: String, val isHome: Boolean)
data class CardInfo(val name: String, val time: String, val shirt: String?, val color: String, val isHome: Boolean)
data class PlayerInfo(val name: String, val shirt: String?)
data class TeamLineupInfo(val formation: String?, val starters: List<PlayerInfo>)
data class LineupInfo(val home: TeamLineupInfo, val away: TeamLineupInfo)
