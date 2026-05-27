package com.atleti.news.repository

import com.atleti.news.entity.ArticleViewLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface ArticleViewLogRepository : JpaRepository<ArticleViewLog, Long> {
    fun existsByArticleIdAndIpAddressAndViewedAtAfter(
        articleId: Long,
        ipAddress: String,
        after: OffsetDateTime
    ): Boolean

    fun deleteByViewedAtBefore(before: OffsetDateTime)
}
