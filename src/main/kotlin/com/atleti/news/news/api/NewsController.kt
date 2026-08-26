package com.atleti.news.news.api

import com.atleti.news.news.domain.ArticleType
import com.atleti.news.news.domain.CollectionLog
import com.atleti.news.news.domain.CollectionStatus
import com.atleti.news.news.domain.NewsSource
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.CollectionLogRepository
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.NewsSourceRepository
import com.atleti.news.news.domain.NewsPipelineService
import com.atleti.news.news.domain.ViewCountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import java.time.OffsetDateTime

@Tag(name = "News API", description = "아틀레티코 뉴스 조회 및 관리 API")
@RestController
@RequestMapping("/api/news")
class NewsController(
    private val newsArticleRepository: NewsArticleRepository,
    private val newsSourceRepository: NewsSourceRepository,
    private val collectionLogRepository: CollectionLogRepository,
    private val viewCountService: ViewCountService,
    private val newsPipelineService: NewsPipelineService
) {

    @Operation(summary = "뉴스 목록 조회 (필터링 및 검색 포함)", description = "뉴스 목록을 가져옵니다. 소스 리스트, 카테고리, 그리고 검색어(제목/본문) 필터링이 가능합니다.")
    @GetMapping
    fun getNews(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) sources: List<String>?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "false") isHyped: Boolean
    ): Page<NewsListResponse> {
        val lowerSources = sources?.map { it.lowercase() }
        
        val articles = if (isHyped) {
            // 인기 기사: 최근 3일간 조회수 30회 이상인 기사 (조회수 높은 순 -> 최신순 정렬)
            val pageable = PageRequest.of(page, size, Sort.by("viewCount").descending().and(Sort.by("publishedAt").descending()))
            val threeDaysAgo = OffsetDateTime.now().minusDays(3)
            newsArticleRepository.searchHypedNews(30L, threeDaysAgo, lowerSources, category, pageable)
        } else {
            // 일반 목록: 최신순 정렬
            val pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending())
            newsArticleRepository.searchNews(lowerSources, category, search, pageable)
        }

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
                imageUrl = it.imageUrl,
                viewCount = it.viewCount
            )
        }
    }

    @Operation(summary = "수집 소스 목록 조회", description = "시스템에서 관리 중인 모든 뉴스 수집원 리스트를 가져옵니다.")
    @GetMapping("/sources")
    fun getSources(): List<NewsSourceResponse> {
        return newsSourceRepository.findAllByIsActiveTrue().map { NewsSourceResponse.of(it) }
    }

    @Operation(summary = "뉴스 상세 조회 (전체 포함)", description = "특정 기사의 상세 정보(이미지, 본문 포함)를 가져옵니다.")
    @GetMapping("/{id}")
    fun getNewsDetail(@PathVariable id: Long, request: HttpServletRequest): NewsDetailResponse {
        val article = newsArticleRepository.findById(id)
            .orElseThrow { NoSuchElementException("해당 기사를 찾을 수 없습니다. ID: $id") }
        
        // 조회수 증가 로직 (IP 기반 24시간 중복 방지 — Redis SETNX, docs/redis-design.md Phase 1)
        val ip = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr

        if (viewCountService.tryMarkViewed(id, ip)) {
            article.viewCount += 1
            newsArticleRepository.save(article)
        }
        
        return NewsDetailResponse(
            id = article.id!!,
            titleKo = article.titleKo ?: "제목 없음",
            summaryKo = article.summaryKo ?: "", 
            contentKo = article.contentKo ?: "본문 내용이 없습니다.",
            imageUrl = article.imageUrl,
            sourceName = article.sourceName,
            authorName = article.authorName ?: article.sourceName,
            originalUrl = article.originalUrl,
            publishedAt = article.publishedAt,
            articleType = article.articleType,
            category = article.category ?: "[기타]",
            viewCount = article.viewCount
        )
    }

    @Operation(summary = "수집 로그 조회", description = "뉴스 수집 배치 실행 기록을 확인합니다.")
    @GetMapping("/logs")
    fun getLogs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): Page<CollectionLogResponse> {
        val pageable = PageRequest.of(page, size, Sort.by("createdAt").descending())
        return collectionLogRepository.findAll(pageable).map { CollectionLogResponse.of(it) }
    }

    @Operation(summary = "배치 수동 실행", description = "뉴스 수집 파이프라인을 즉시 실행합니다.")
    @PostMapping("/trigger")
    fun triggerPipeline() {
        newsPipelineService.runPipeline()
    }
}

/**
 * 수집 소스 응답 DTO.
 *
 * 이전에는 [NewsSource] 엔티티를 그대로 직렬화했다. 그러면 컬럼을 추가하거나 이름을 바꾸는
 * 순간 API 응답이 같이 바뀌어, **스키마 변경이 곧 API 계약 변경**이 된다
 * (docs/architecture.md 1.2-③).
 *
 * 필드 구성은 엔티티와 1:1이다. 지금 프론트가 이 모양에 의존하고 있어 형태를 바꾸지 않는다 —
 * 목적은 모양을 줄이는 게 아니라 **엔티티와 응답을 끊어 두는 것**이다.
 * 두 JSON이 같다는 것은 `NewsResponseContractTest`가 고정한다.
 */
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

/**
 * 수집 로그 응답 DTO. [NewsSourceResponse]와 같은 이유로 [CollectionLog] 엔티티를 대신한다.
 */
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
    val imageUrl: String?,
    val viewCount: Long
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
    val category: String?,
    val viewCount: Long
)
