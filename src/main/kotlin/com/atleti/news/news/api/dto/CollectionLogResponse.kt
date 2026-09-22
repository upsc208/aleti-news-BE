package com.atleti.news.news.api.dto

import com.atleti.news.news.domain.CollectionLog
import com.atleti.news.news.domain.CollectionStatus
import java.time.OffsetDateTime

/* 수집 로그 응답 DTO */
data class CollectionLogResponse(
    val id: Long?,
    val status: CollectionStatus,
    val scannedCount: Int,
    val aiProcessedCount: Int,
    val twitterApiCallCount: Int,
    val footballApiCallCount: Int,
    val errorMsg: String?,
    val executionTimeMs: Long,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun of(e: CollectionLog) = CollectionLogResponse(
            id = e.id,
            status = e.status,
            scannedCount = e.scannedCount,
            aiProcessedCount = e.aiProcessedCount,
            twitterApiCallCount = e.twitterApiCallCount,
            footballApiCallCount = e.footballApiCallCount,
            errorMsg = e.errorMsg,
            executionTimeMs = e.executionTimeMs,
            createdAt = e.createdAt
        )
    }
}
