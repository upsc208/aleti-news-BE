package com.atleti.news.repository

import com.atleti.news.entity.UpcomingMatch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UpcomingMatchRepository : JpaRepository<UpcomingMatch, Long> {
    fun findAllByOrderByMatchDateAsc(): List<UpcomingMatch>
    fun findByMatchId(matchId: String): UpcomingMatch?
}
