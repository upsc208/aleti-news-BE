package com.atleti.news.repository

import com.atleti.news.entity.UpcomingMatchEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UpcomingMatchRepository : JpaRepository<UpcomingMatchEntity, Long> {
    fun findAllByOrderByMatchDateAsc(): List<UpcomingMatchEntity>
    fun findByMatchId(matchId: String): UpcomingMatchEntity?
}
