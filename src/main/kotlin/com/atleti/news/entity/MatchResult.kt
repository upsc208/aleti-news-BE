package com.atleti.news.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "match_result")
class MatchResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val matchDate: String,
    val tournament: String,
    val isHome: Boolean,

    @Column(columnDefinition = "TEXT")
    val scorersJson: String? = null, // 득점자 정보를 JSON 문자열로 저장

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
