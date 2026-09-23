package com.atleti.news.news.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface DeletedArticleRepository : JpaRepository<DeletedArticle, Long> {
    fun existsByOriginalUrl(originalUrl: String): Boolean

    fun deleteByDeletedAtBefore(cutoff: OffsetDateTime): Long
}
