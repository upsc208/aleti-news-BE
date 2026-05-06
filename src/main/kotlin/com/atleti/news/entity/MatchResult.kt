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

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
