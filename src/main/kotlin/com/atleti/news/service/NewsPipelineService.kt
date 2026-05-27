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
import java.util.concurrent.atomic.AtomicInteger

@Service
class NewsPipelineService(
    private val scraperService: ScraperService,
    private val twitterApiService: TwitterApiService,
    private val nitterScraperService: NitterScraperService,
    private val geminiService: GeminiService,
    private val youtubeService: YoutubeService,
    private val newsArticleRepository: NewsArticleRepository,
    private val newsSourceRepository: NewsSourceRepository,
    private val collectionLogRepository: CollectionLogRepository,
    private val articleViewLogRepository: com.atleti.news.repository.ArticleViewLogRepository,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.keywords}") private val keywordString: String,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.twitter}") private val isTwitterEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.news}") private val isNewsEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.gemini}") private val isGeminiEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.youtube}") private val isYoutubeEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${rapidapi.daily.limit}") private val twitterDailyLimit: Int
    ) {
    private val keywords = keywordString.split(",").map { it.trim().lowercase() }

    // 고정 15분 간격 스케줄러 (24시간 내내)
    @Scheduled(cron = "0 0/15 * * * *")
    fun scheduledPipeline() = runPipeline()

    // 유튜브는 1시간에 한 번 정도만 체크 (매번 하면 SerpApi 쿼터 낭비)
    @Scheduled(cron = "0 5 * * * *")
    fun scheduledYoutubePipeline() = runBlocking {
        if (!isYoutubeEnabled) return@runBlocking
        println("📺 유튜브 파이프라인 시작...")
        youtubeService.runYoutubePipeline("@rubenuriagonzalez")
    }

    // 매일 새벽 4시에 오래된 조회 로그 청소 (조회수 중복 방지용)
    @Scheduled(cron = "0 0 4 * * *")
    @org.springframework.transaction.annotation.Transactional
    fun cleanOldData() {
        // 조회수 중복 방지 로그는 1일치만 남기고 삭제 (DB 용량 절약 및 개인정보 관리)
        val logCutoffDate = java.time.OffsetDateTime.now().minusDays(1)
        articleViewLogRepository.deleteByViewedAtBefore(logCutoffDate)
        println("🧹 1일 경과 조회 로그를 청소했습니다.")
    }


    fun runPipeline() = runBlocking {
        val scannedCount = AtomicInteger(0)
        val aiProcessedCount = AtomicInteger(0)
        val twitterApiCallCount = AtomicInteger(0)
        val startTime = System.currentTimeMillis()
        val geminiSemaphore = Semaphore(2)
        val scrapingSemaphore = Semaphore(2)

        try {
            coroutineScope {
                val activeSources = newsSourceRepository.findAllByIsActiveTrue()
                val journalists = activeSources.filter { it.category != "MEDIA" }
                val mediaSources = activeSources.filter { it.category == "MEDIA" }
                
                val initialTwitterUsage = if (isTwitterEnabled) twitterApiService.getTodayApiUsage() else 0
                println("🚀 파이프라인 시작 (Twitter: $isTwitterEnabled, 오늘 Twitter API 사용: $initialTwitterUsage/$twitterDailyLimit)")

                val deferredArticles = mutableListOf<Deferred<List<ScrapedArticle>>>()

                // 1. 일반 웹 매체 수집
                if (isNewsEnabled) {
                    mediaSources.forEach { source ->
                        deferredArticles.add(async {
                            val scraped = when (source.internalId) {
                                "marca" -> scraperService.scrapeMarcaAtm()
                                "as" -> scraperService.scrapeAsAtm()
                                else -> emptyList()
                            }
                            scraped
                        })
                    }
                }

                // 2. Twitter 기자 전수 조사 (로테이션 제거)
                if (isTwitterEnabled && journalists.isNotEmpty()) {
                    journalists.forEach { journalist ->
                        deferredArticles.add(async {
                            val username = journalist.internalId
                            var tweets = emptyList<ScrapedArticle>()
                            
                            // 매번 잔여 쿼터 확인
                            val currentUsage = initialTwitterUsage + twitterApiCallCount.get()
                            if (currentUsage < twitterDailyLimit) {
                                try {
                                    tweets = twitterApiService.getTweets(username)
                                    if (tweets.isNotEmpty()) twitterApiCallCount.incrementAndGet()
                                } catch (e: Exception) {
                                    println("❌ [RapidAPI] @$username 오류: ${e.message}")
                                }
                            }
                            tweets
                        })
                    }
                }

                val allScraped = deferredArticles.awaitAll().flatten()
                val uniqueArticles = allScraped.distinctBy { it.url }
                val sortedArticles = uniqueArticles.sortedBy { it.publishedAt }
                scannedCount.set(sortedArticles.size)

                // 3. 병렬 처리 및 저장
                sortedArticles.map { article ->
                    async {
                        processSingleArticle(article, aiProcessedCount, geminiSemaphore, scrapingSemaphore)
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
        geminiSemaphore: Semaphore,
        scrapingSemaphore: Semaphore
    ) {
        if (newsArticleRepository.existsByOriginalUrl(article.url)) return

        val isTwitter = article.articleType == com.atleti.news.entity.ArticleType.SNS_POST
        val isDedicatedMedia = article.sourceName == "Marca" || article.sourceName == "AS"

        if (!isTwitter && !isDedicatedMedia) {
            val lowerTitle = article.title.lowercase()
            if (keywords.none { lowerTitle.contains(it) }) return
        }

        val detailResult = if (isDedicatedMedia) {
            try {
                scrapingSemaphore.withPermit {
                    val detail = scraperService.fetchFullContent(article.url)
                    if (detail.content.isBlank() || detail.content.length < 50) null else detail
                } ?: return
            } catch (e: Exception) { return }
        } else {
            if (article.title.isBlank()) return
            ArticleDetail(content = article.title, authorName = article.authorName ?: "Unknown", publishedAt = article.publishedAt, imageUrl = null)
        }

        if (!isGeminiEnabled) return

        val geminiResult = geminiSemaphore.withPermit {
            geminiService.processArticle(article.title, detailResult.content.take(10000), article.sourceName, skipValidation = true)
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
                        category = geminiResult.category,
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

    private fun saveLog(status: CollectionStatus, scanned: Int, aiProcessed: Int, apiCallCount: Int, startTime: Long, errorMsg: String? = null) {
        collectionLogRepository.save(
            CollectionLog(
                status = status, scannedCount = scanned, aiProcessedCount = aiProcessed,
                twitterApiCallCount = apiCallCount, errorMsg = errorMsg,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        )
    }
}
