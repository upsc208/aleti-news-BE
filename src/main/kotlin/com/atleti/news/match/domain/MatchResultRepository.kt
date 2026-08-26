package com.atleti.news.match.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MatchResultRepository : JpaRepository<MatchResult, Long> {
    fun findTopByOrderByMatchDateDesc(): MatchResult?
    fun findAllByOrderByMatchDateDesc(): List<MatchResult>
    fun existsByMatchId(matchId: String): Boolean
    fun findByMatchId(matchId: String): MatchResult?
}
