package com.atleti.news.rating.domain

/* 한 선수의 평점 현황 — 투표 집계와 이 사람이 준 점수를 합친 결과 */
data class PlayerRating(
    val playerName: String,
    val shirt: String?,
    val averageScore: Double,
    val voteCount: Long,
    val myScore: Int?
)
