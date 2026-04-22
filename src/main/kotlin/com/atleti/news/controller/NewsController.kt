package com.atleti.news.controller

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.NewsArticle
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.NewsArticleRepository
import com.atleti.news.service.NewsPipelineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.*

@Tag(name = "News API", description = "아틀레티코 뉴스 조회 및 관리 API")
@RestController
@RequestMapping("/api/news")
class NewsController(
    private val newsArticleRepository: NewsArticleRepository,
    private val collectionLogRepository: CollectionLogRepository,
    private val newsPipelineService: NewsPipelineService
) {

    @Operation(summary = "뉴스 목록 조회", description = "최신순으로 페이징된 뉴스 목록을 가져옵니다.")
    @GetMapping
    fun getNews(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): Page<NewsArticle> {
        val pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending())
        return newsArticleRepository.findAll(pageable)
    }

    @Operation(summary = "수집 로그 조회", description = "뉴스 수집 배치 실행 기록을 확인합니다.")
    @GetMapping("/logs")
    fun getLogs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): Page<CollectionLog> {
        val pageable = PageRequest.of(page, size, Sort.by("createdAt").descending())
        return collectionLogRepository.findAll(pageable)
    }

    @Operation(summary = "배치 수동 실행", description = "뉴스 수집 파이프라인을 즉시 실행합니다.")
    @PostMapping("/trigger")
    fun triggerPipeline() {
        newsPipelineService.runPipeline()
    }
}
