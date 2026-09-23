package com.atleti.news.news.api

import com.atleti.news.news.api.dto.HeadlineResponse
import com.atleti.news.news.domain.NewsHeadlineRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
