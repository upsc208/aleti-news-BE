package com.atleti.news.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "match_result", uniqueConstraints = [UniqueConstraint(columnNames = ["matchId"])])
class MatchResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false)
    val matchId: String,
    
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val matchDate: String,
    val tournament: String,
    val isHome: Boolean,
    
    @Column(columnDefinition = "TEXT")
    val scorers: String? = null,
    
    // 추가된 핵심 통계 지표들
    @Column(columnDefinition = "TEXT")
    val lineup: String? = null, // 선발 명단 포맷팅 텍스트
    @Column(columnDefinition = "TEXT")
    val cards: String? = null, // 경고 및 퇴장 정보 (예: "Koke 35' (Y), Gimenez 88' (R)")
    val possession: String? = null, // 예: "45 - 55"
    val shotsOnTarget: String? = null, // 예: "5 - 3"
    val xg: String? = null, // 예: "1.24 - 0.85"

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
