package com.atleti.news.controller

import com.atleti.news.repository.NewsHeadlineRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@Tag(name = "Headline API", description = "실시간 헤드라인 조회 API")
@RestController
@RequestMapping("/api/headlines")
class HeadlineController(
    private val newsHeadlineRepository: NewsHeadlineRepository
) {

    @Operation(summary = "헤드라인 목록 조회", description = "최신순으로 현재 노출 중인 헤드라인(최대 5개)을 가져옵니다.")
    @GetMapping
    fun getHeadlines(): List<HeadlineResponse> {
        return newsHeadlineRepository.findTop5ByOrderByCreatedAtDesc().map {
            HeadlineResponse(
                id = it.id!!,
                content = it.content,
                createdAt = it.createdAt
            )
        }
    }
}

/**
 * 헤드라인 조회용 응답 DTO
 */
data class HeadlineResponse(
    val id: Long,
    val content: String,
    val createdAt: OffsetDateTime
)
