package com.atleti.news.match.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "upcoming_match",
    uniqueConstraints = [UniqueConstraint(columnNames = ["opponent", "matchDate"])]
)
class UpcomingMatch(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // 외부 API의 경기 id — MatchResult.matchId와 같은 체계. 채팅방 키로 사용 (docs/redis-design.md 6.6)
    val matchId: String? = null,

    @Column(nullable = false)
    val opponent: String,

    @Column(nullable = false)
    val matchDate: String,
    
    val isHome: Boolean,
    val tournament: String,

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
