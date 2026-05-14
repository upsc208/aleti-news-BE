package com.atleti.news.controller

import com.atleti.news.entity.ArticleType
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
import java.time.OffsetDateTime

@Tag(name = "News API", description = "아틀레티코 뉴스 조회 및 관리 API")
@RestController
@RequestMapping("/api/news")
class NewsController(
    private val newsArticleRepository: NewsArticleRepository,
    private val newsSourceRepository: com.atleti.news.repository.NewsSourceRepository,
    private val collectionLogRepository: CollectionLogRepository,
    private val newsPipelineService: NewsPipelineService
) {

    @Operation(summary = "뉴스 목록 조회 (필터링 및 검색 포함)", description = "뉴스 목록을 가져옵니다. 소스 리스트, 카테고리, 그리고 검색어(제목/본문) 필터링이 가능합니다.")
    @GetMapping
    fun getNews(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) sources: List<String>?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) search: String?
    ): Page<NewsListResponse> {
        val pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending())
        
        val lowerSources = sources?.map { it.lowercase() }
        val articles = newsArticleRepository.searchNews(lowerSources, category, search, pageable)

        return articles.map { 
            NewsListResponse(
                id = it.id!!,
                titleKo = it.titleKo,
                sourceName = it.sourceName,
                authorName = it.authorName,
                publishedAt = it.publishedAt,
                articleType = it.articleType,
                summaryKo = it.summaryKo,
                category = it.category,
                imageUrl = it.imageUrl
            )
        }
    }

    @Operation(summary = "수집 소스 목록 조회", description = "시스템에서 관리 중인 모든 뉴스 수집원 리스트를 가져옵니다.")
    @GetMapping("/sources")
    fun getSources(): List<com.atleti.news.entity.NewsSource> {
        return newsSourceRepository.findAllByIsActiveTrue()
    }

    @Operation(summary = "뉴스 상세 조회 (전체 포함)", description = "특정 기사의 상세 정보(이미지, 본문 포함)를 가져옵니다.")
    @GetMapping("/{id}")
    fun getNewsDetail(@PathVariable id: Long): NewsDetailResponse {
        val article = newsArticleRepository.findById(id)
            .orElseThrow { NoSuchElementException("해당 기사를 찾을 수 없습니다. ID: $id") }
        
        return NewsDetailResponse(
            id = article.id!!,
            titleKo = article.titleKo,
            summaryKo = article.summaryKo,
            contentKo = article.contentKo,
            imageUrl = article.imageUrl,
            sourceName = article.sourceName,
            authorName = article.authorName,
            originalUrl = article.originalUrl,
            publishedAt = article.publishedAt,
            articleType = article.articleType,
            category = article.category
        )
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

/**
 * 목록 조회용 응답 DTO
 */
data class NewsListResponse(
    val id: Long,
    val titleKo: String?,
    val sourceName: String,
    val authorName: String?,
    val publishedAt: OffsetDateTime?,
    val articleType: ArticleType,
    val summaryKo: String?,
    val category: String?,
    val imageUrl: String?
)

/**
 * 상세 조회용 응답 DTO (모든 정보 포함)
 */
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
    val category: String?
)
