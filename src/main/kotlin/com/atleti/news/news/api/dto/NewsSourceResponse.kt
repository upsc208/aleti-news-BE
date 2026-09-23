package com.atleti.news.news.api.dto

import com.atleti.news.news.domain.NewsSource

/* 수집 소스 응답 DTO — 엔티티와 1:1이지만 스키마 변경이 API로 새지 않게 끊어 둔 것 */
data class NewsSourceResponse(
    val id: Long?,
    val internalId: String,
    val displayName: String,
    val sourceUrl: String?,
    val iconUrl: String?,
    val category: String,
    val isActive: Boolean
) {
    companion object {
        fun of(e: NewsSource) = NewsSourceResponse(
            id = e.id,
            internalId = e.internalId,
            displayName = e.displayName,
            sourceUrl = e.sourceUrl,
            iconUrl = e.iconUrl,
            category = e.category,
            isActive = e.isActive
        )
    }
}
