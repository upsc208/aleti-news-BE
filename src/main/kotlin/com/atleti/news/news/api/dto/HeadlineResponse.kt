package com.atleti.news.news.api.dto

import java.time.OffsetDateTime

/* 헤드라인 조회용 응답 DTO */
data class HeadlineResponse(
    val id: Long,
    val content: String,
    val createdAt: OffsetDateTime
)
