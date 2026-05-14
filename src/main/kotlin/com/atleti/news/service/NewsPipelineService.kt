package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.entity.NewsArticle
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.NewsArticleRepository
import com.atleti.news.repository.NewsSourceRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

@Service
class NewsPipelineService(
    private val scraperService: ScraperService,
    private val twitterApiService: TwitterApiService,
    private val nitterScraperService: NitterScraperService,
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val newsSourceRepository: NewsSourceRepository,
    private val collectionLogRepository: CollectionLogRepository,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.keywords}") private val keywordString: String,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.twitter}") private val isTwitterEnabled: Boolean
) {
    private val keywords = keywordString.split(",").map { it.trim().lowercase() }
    
    // 로테이션을 위한 인덱스 (서버 재시작 시 리셋되지만 33회 제한 내에서 충분히 작동함)
    companion object {
        private var journalistRotationIndex = 0
    }

    @Scheduled(cron = "0 0/15 9-23 * * *", zone = "Europe/Madrid") // 스페인 시간 09:00~23:45 사이 15분마다
    fun scheduledFastPipeline() = runPipeline()

    @Scheduled(cron = "0 0 0-8 * * *", zone = "Europe/Madrid") // 스페인 시간 00:00~08:00 사이 1시간마다
    fun scheduledSlowPipeline() = runPipeline()

    fun runPipeline() = runBlocking {
        val scannedCount = AtomicInteger(0)
        val aiProcessedCount = AtomicInteger(0)
        val twitterApiCallCount = AtomicInteger(0)
        val startTime = System.currentTimeMillis()
        val geminiSemaphore = Semaphore(3)

        try {
            coroutineScope {
                // 1. 활성 소스 로드
                val activeSources = newsSourceRepository.findAllByIsActiveTrue()
                val journalists = activeSources.filter { it.category != "MEDIA" }
                val mediaSources = activeSources.filter { it.category == "MEDIA" }
                
                val initialTwitterUsage = if (isTwitterEnabled) twitterApiService.getTodayApiUsage() else 0
                println("🚀 파이프라인 시작 (Twitter 활성여부: $isTwitterEnabled, 오늘 Twitter API 사용: $initialTwitterUsage/33)")

                val deferredArticles = mutableListOf<Deferred<List<ScrapedArticle>>>()

                // [중요] 2. Marca 등 일반 웹 매체는 매번 수집 (쿼터 무제한)
                mediaSources.forEach { source ->
                    deferredArticles.add(async {
                        println("🔍 ${source.displayName} 수집 시작...")
                        if (source.internalId == "marca") {
                            scraperService.scrapeMarcaAtm()
                        } else if (source.internalId == "as") {
                            scraperService.scrapeAsAtm()
                        } else {
                            emptyList<ScrapedArticle>()
                        }
                    })
                }

                // [중요] 3. Twitter 기자는 '로테이션' 방식으로 이번 회차의 주인공 1명만 선정
                if (isTwitterEnabled && journalists.isNotEmpty()) {
                    val targetJournalist = journalists[journalistRotationIndex % journalists.size]
                    journalistRotationIndex++
                    
                    deferredArticles.add(async {
                        val username = targetJournalist.internalId
                        println("🎯 이번 회차 트위터 타겟: @$username (순번: ${journalistRotationIndex})")
                        
                        var tweets = emptyList<ScrapedArticle>()
                        
                        // RapidAPI 한도 체크
                        if (initialTwitterUsage < 33) {
                            try {
                                tweets = twitterApiService.getTweets(username)
                                if (tweets.isNotEmpty()) twitterApiCallCount.incrementAndGet()
                            } catch (e: Exception) {
                                println("❌ [RapidAPI] @$username 오류: ${e.message}")
                            }
                        } else {
                            println("⏭️ [RapidAPI] 오늘 한도 초과(33회), 수집을 건너뜁니다.")
                        }
                        tweets
                    })
                } else if (!isTwitterEnabled) {
                    println("⏭️ 트위터 수집이 비활성화 상태입니다.")
                }

                val allScraped = deferredArticles.awaitAll().flatten()
                val uniqueArticles = allScraped.distinctBy { it.url }
                val sortedArticles = uniqueArticles.sortedBy { it.publishedAt }
                scannedCount.set(sortedArticles.size)

                // 4. 병렬 처리 및 저장
                sortedArticles.map { article ->
                    async {
                        processSingleArticle(article, aiProcessedCount, geminiSemaphore)
                    }
                }.awaitAll()
            }
            saveLog(CollectionStatus.SUCCESS, scannedCount.get(), aiProcessedCount.get(), twitterApiCallCount.get(), startTime)
        } catch (e: Exception) {
            println("❌ 파이프라인 중단: ${e.message}")
            saveLog(CollectionStatus.FAIL, scannedCount.get(), aiProcessedCount.get(), twitterApiCallCount.get(), startTime, e.message)
        }
    }

    private suspend fun processSingleArticle(
        article: ScrapedArticle,
        aiProcessedCount: AtomicInteger,
        geminiSemaphore: Semaphore
    ) {
        if (newsArticleRepository.existsByOriginalUrl(article.url)) return

        val isTwitter = article.sourceName.contains("X (", ignoreCase = true) || 
                        article.articleType == com.atleti.news.entity.ArticleType.SNS_POST
        
        val isDedicatedMedia = article.sourceName == "Marca" || article.sourceName == "AS"

        if (!isTwitter && !isDedicatedMedia) {
            val lowerTitle = article.title.lowercase()
            if (keywords.none { lowerTitle.contains(it) }) return
        }

        val detailResult = if (article.sourceName == "Marca" || article.sourceName == "AS") {
            try {
                val detail = scraperService.fetchFullContent(article.url)
                if (detail.content.isBlank() || detail.content.length < 50) {
                    println("⏭️ 본문 내용이 너무 짧거나 비어있습니다. (${article.url})")
                    return
                }
                detail
            } catch (e: Exception) {
                println("⏭️ 상세 정보 추출 실패로 수집을 건너뜁니다: ${article.url}")
                return
            }
        } else {
            // 트위터 등의 경우 title이 곧 본문이 됨
            if (article.title.isBlank()) return
            ArticleDetail(
                content = article.title,
                authorName = article.authorName ?: "Unknown",
                publishedAt = article.publishedAt,
                imageUrl = null
            )
        }

        val geminiResult = geminiSemaphore.withPermit {
            geminiService.processArticle(article.title, detailResult.content, article.sourceName, skipValidation = true)
        }

        if (geminiResult != null && (isTwitter || geminiResult.is_atm_related)) {
            try {
                newsArticleRepository.save(
                    NewsArticle(
                        articleType = article.articleType,
                        sourceName = article.sourceName,
                        authorName = detailResult.authorName,
                        originalUrl = article.url,
                        titleKo = geminiResult.title_ko,
                        summaryKo = geminiResult.summary_ko,
                        contentKo = geminiResult.content_ko,
                        contentRaw = detailResult.content,
                        imageUrl = detailResult.imageUrl ?: article.imageUrl,
                        category = geminiResult.category, // 새 필드 저장
                        isAtmRelated = true,
                        publishedAt = detailResult.publishedAt 
                    )
                )
                aiProcessedCount.incrementAndGet()
            } catch (e: Exception) {
                println("❌ 저장 실패: ${e.message}")
            }
        }
    }

    private fun saveLog(
        status: CollectionStatus,
        scanned: Int,
        aiProcessed: Int,
        apiCallCount: Int,
        startTime: Long,
        errorMsg: String? = null
    ) {
        collectionLogRepository.save(
            CollectionLog(
                status = status,
                scannedCount = scanned,
                aiProcessedCount = aiProcessed,
                twitterApiCallCount = apiCallCount,
                errorMsg = errorMsg,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        )
    }
}
