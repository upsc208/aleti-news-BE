package com.atleti.news.news.api.dto

import com.atleti.news.news.domain.ArticleType
import java.time.OffsetDateTime

/* 기사 목록 응답 DTO */
data class NewsListResponse(
    val id: Long,
    val titleKo: String?,
    val sourceName: String,
    val authorName: String?,
    val publishedAt: OffsetDateTime?,
    val articleType: ArticleType,
    val summaryKo: String?,
    val category: String?,
    val imageUrl: String?,
    val viewCount: Long
)
