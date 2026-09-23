package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.atleti.news.news.external.ArticleDetail
import com.atleti.news.news.external.GeminiService
import com.atleti.news.news.external.NitterScraperService
import com.atleti.news.news.external.ScrapedArticle
import com.atleti.news.news.external.ScraperService
import com.atleti.news.news.external.TwitterApiService

@Service
class NewsPipelineService(
    private val scraperService: ScraperService,
    private val twitterApiService: TwitterApiService,
    private val nitterScraperService: NitterScraperService,
    private val geminiService: GeminiService,
    private val youtubeService: YoutubeService,
    private val headlineService: HeadlineService,
    private val newsArticleRepository: NewsArticleRepository,
    private val articleDeletionService: ArticleDeletionService,
    private val newsSourceRepository: NewsSourceRepository,
    private val collectionLogRepository: CollectionLogRepository,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.keywords}") private val keywordString: String,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.twitter}") private val isTwitterEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.news}") private val isNewsEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.gemini}") private val isGeminiEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.youtube}") private val isYoutubeEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.enabled.headline}") private val isHeadlineEnabled: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${rapidapi.daily.limit}") private val twitterDailyLimit: Int
    ) {
    private val log = LoggerFactory.getLogger(NewsPipelineService::class.java)

    private val keywords = keywordString.split(",").map { it.trim().lowercase() }

    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    // 고정 15분 간격 스케줄러 (24시간 내내)
    @Scheduled(cron = "0 0/15 * * * *")
    fun scheduledPipeline() = runPipeline()

    // 유튜브는 1시간에 한 번 정도만 체크
    @Scheduled(cron = "0 5 * * * *")
    fun scheduledYoutubePipeline() = runBlocking {
        if (!isYoutubeEnabled) return@runBlocking
        log.info("📺 유튜브 파이프라인 시작...")
        youtubeService.runYoutubePipeline("@rubenuriagonzalez")
    }

    // 헤드라인 브리핑: 6시간마다 최신 뉴스를 종합해 헤드라인 1~2개 생성 (최신 5개 롤링 유지)
    @Scheduled(cron = "0 30 0/6 * * *")
    fun scheduledHeadlinePipeline() = runBlocking {
        if (!isHeadlineEnabled) return@runBlocking
        log.info("📰 헤드라인 파이프라인 시작...")
        headlineService.generateAndStore()
    }

    // 조회 로그 청소 배치는 Redis 전환으로 제거됨 — 중복 방지 마커가 TTL 24h로 자동 소멸

    fun runPipeline() = runBlocking { pipeline() }

    // 관리자 수동 실행
    fun startPipeline(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        manualScope.launch {
            try {
                pipeline()
            } finally {
                running.set(false)
            }
        }
        return true
    }

    private suspend fun pipeline() {
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
                log.info("🚀 파이프라인 시작 (Twitter: $isTwitterEnabled, 오늘 Twitter API 사용: $initialTwitterUsage/$twitterDailyLimit)")

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

                // 2. Twitter 기자 전수 조사
                if (isTwitterEnabled && journalists.isNotEmpty()) {
                    journalists.forEach { journalist ->
                        deferredArticles.add(async {
                            val username = journalist.internalId
                            var tweets = emptyList<ScrapedArticle>()
                            
                            val currentUsage = initialTwitterUsage + twitterApiCallCount.get()
                            if (currentUsage < twitterDailyLimit) {
                                try {
                                    tweets = twitterApiService.getTweets(username)
                                    if (tweets.isNotEmpty()) twitterApiCallCount.incrementAndGet()
                                } catch (e: Exception) {
                                    log.error("❌ [RapidAPI] @$username 오류: ${e.message}", e)
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
            log.error("❌ 파이프라인 중단: ${e.message}", e)
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
        if (articleDeletionService.isDeleted(article.url)) return

        val isTwitter = article.articleType == ArticleType.SNS_POST
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

        if (geminiResult != null && (isTwitter || geminiResult.isAtmRelated)) {
            try {
                val titleKo = geminiResult.titleKo?.let { if (article.isRetweet) "[리트윗] $it" else it }
                val contentKo = geminiResult.contentKo?.let {
                    if (article.isRetweet) "🔁 ${article.authorName ?: article.sourceName}님이 리트윗한 글입니다.\n\n$it" else it
                }
                newsArticleRepository.save(
                    NewsArticle(
                        articleType = article.articleType,
                        sourceName = article.sourceName,
                        authorName = detailResult.authorName,
                        originalUrl = article.url,
                        titleKo = titleKo,
                        summaryKo = geminiResult.summaryKo,
                        contentKo = contentKo,
                        contentRaw = detailResult.content,
                        imageUrl = detailResult.imageUrl ?: article.imageUrl,
                        category = geminiResult.category,
                        isAtmRelated = true,
                        publishedAt = detailResult.publishedAt 
                    )
                )
                aiProcessedCount.incrementAndGet()
            } catch (e: Exception) {
                log.error("❌ 저장 실패: ${e.message}", e)
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
