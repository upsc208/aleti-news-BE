package com.atleti.news.news.api.dto

import com.atleti.news.news.domain.ArticleType
import java.time.OffsetDateTime

/* 기사 상세 응답 DTO */
data class NewsDetailResponse(
    val id: Long,
    val titleKo: String?,
    val summaryKo: String?,
    val contentKo: String?,
    val imageUrl: String?,
    val sourceName: String,
    val authorName: String?,
    val originalUrl: String,
    val publishedAt: OffsetDateTime?,
    val articleType: ArticleType,
    val category: String?,
    val viewCount: Long
)
