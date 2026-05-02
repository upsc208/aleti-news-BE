package com.atleti.news.repository

import com.atleti.news.entity.MatchResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MatchResultRepository : JpaRepository<MatchResult, Long> {
    fun findTopByOrderByMatchDateDesc(): MatchResult?
    fun findAllByOrderByMatchDateDesc(): List<MatchResult>
}
