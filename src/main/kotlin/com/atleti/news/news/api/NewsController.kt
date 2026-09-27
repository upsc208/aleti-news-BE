package com.atleti.news.news.api

import com.atleti.news.common.ClientIp
import com.atleti.news.common.NotFoundException
import com.atleti.news.news.api.dto.NewsDetailResponse
import com.atleti.news.news.api.dto.NewsListResponse
import com.atleti.news.news.api.dto.NewsSourceResponse
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.NewsSourceRepository
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
    private val viewCountService: ViewCountService
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
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)

        val articles = if (isHyped) {
            // 인기 기사: 최근 3일간 조회수 30회 이상인 기사 (조회수 높은 순 -> 최신순 정렬)
            val pageable = PageRequest.of(safePage, safeSize, Sort.by("viewCount").descending().and(Sort.by("publishedAt").descending()))
            val threeDaysAgo = OffsetDateTime.now().minusDays(3)
            newsArticleRepository.searchHypedNews(30L, threeDaysAgo, lowerSources, category, pageable)
        } else {
            // 일반 목록: 최신순 정렬
            val pageable = PageRequest.of(safePage, safeSize, Sort.by("publishedAt").descending())
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
            .orElseThrow { NotFoundException("해당 기사를 찾을 수 없습니다. ID: $id") }

        val counted = viewCountService.countView(id, ClientIp.of(request))
        val viewCount = article.viewCount + if (counted) 1 else 0

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
            viewCount = viewCount
        )
    }

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }
}
