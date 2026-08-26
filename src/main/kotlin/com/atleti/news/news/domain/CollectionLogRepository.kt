package com.atleti.news.news.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface CollectionLogRepository : JpaRepository<CollectionLog, Long> {
    @Query("SELECT SUM(c.twitterApiCallCount) FROM CollectionLog c WHERE c.createdAt >= :startOfDay")
    fun sumTwitterApiCallCountSince(startOfDay: OffsetDateTime): Long?

    @Query("SELECT SUM(c.footballApiCallCount) FROM CollectionLog c WHERE c.createdAt >= :since")
    fun sumFootballApiCallCountSince(since: OffsetDateTime): Long?
}
