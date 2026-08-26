package com.atleti.news.match.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UpcomingMatchRepository : JpaRepository<UpcomingMatch, Long> {
    fun findAllByOrderByMatchDateAsc(): List<UpcomingMatch>
    fun findByMatchId(matchId: String): UpcomingMatch?
}
