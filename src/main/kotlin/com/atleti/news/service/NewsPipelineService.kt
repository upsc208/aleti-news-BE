package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.entity.NewsArticle
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.NewsArticleRepository
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class NewsPipelineService(
    private val scraperService: ScraperService,
    private val twitterApiService: TwitterApiService,
    private val nitterScraperService: NitterScraperService,
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val collectionLogRepository: CollectionLogRepository,
    @org.springframework.beans.factory.annotation.Value("\${atleti.news.keywords}") private val keywordString: String
) {
    private val keywords = keywordString.split(",").map { it.trim().lowercase() }

    @Scheduled(cron = "0 0/15 9-23 * * *", zone = "Europe/Madrid") // 스페인 시간 09:00~23:45 사이 15분마다
    fun scheduledFastPipeline() = runPipeline()

    @Scheduled(cron = "0 0 0-8 * * *", zone = "Europe/Madrid") // 스페인 시간 00:00~08:00 사이 1시간마다
    fun scheduledSlowPipeline() = runPipeline()

    fun runPipeline() = runBlocking {
        var scannedCount = 0
        var aiProcessedCount = 0
        var twitterApiCallCount = 0
        val startTime = System.currentTimeMillis()

        try {
            // 1. Scraping (Marca)
            val articles = mutableListOf<ScrapedArticle>()
            articles.addAll(scraperService.scrapeMarcaAtm())
            println("✅ Marca 수집 완료: ${articles.size}개 발견")

            // 2. Scraping (Twitter/X via RapidAPI Only)
            val journalists = listOf(
                "MatteoMoretto", "atletiuniverse", 
                "RubenUria", "PedroFullanaSER", "MercadoATM_", 
                "EfectoSime1", "medinamarca"
            )
            
            for (username in journalists) {
                println("🔍 Twitter 수집 시도 중: @$username")

                val currentUsage = twitterApiService.getTodayApiUsage()
                var tweets = emptyList<ScrapedArticle>()
                var usedApi = false

                // 1단계: RapidAPI 시도 (한도 내일 경우)
                if (currentUsage + twitterApiCallCount < 45) {
                    try {
                        tweets = twitterApiService.getTweets(username)
                        if (tweets.isNotEmpty()) {
                            usedApi = true
                            twitterApiCallCount++
                            println("✅ [RapidAPI] @$username 에서 ${tweets.size}개의 트윗을 가져왔습니다.")
                        }
                    } catch (e: Exception) {
                        println("❌ [RapidAPI] @$username 수집 중 오류: ${e.message}")
                    }
                } else {
                    println("⏭️ [RapidAPI] 오늘 호출 제한 도달: @$username 스킵 후 Nitter로 전환")
                }

                // 2단계: Fail-over (API 실패하거나 한도 초과 시 Nitter 사용)
                if (tweets.isEmpty()) {
                    println("🔄 [Fail-over] Nitter를 통해 @$username 수집 시도...")
                    try {
                        tweets = nitterScraperService.scrapeJournalist(username)
                        if (tweets.isNotEmpty()) {
                            println("✅ [Nitter] @$username 에서 ${tweets.size}개의 트윗을 가져왔습니다.")
                        }
                    } catch (e: Exception) {
                        println("❌ [Nitter] @$username 수집 중 오류: ${e.message}")
                    }
                }

                if (tweets.isNotEmpty()) {
                    articles.addAll(tweets)
                }

                kotlinx.coroutines.delay(2000)
            }

            // [추가] 발행 시간 기준 오름차순 정렬 (오래된 소식부터 순서대로 처리)
            val sortedArticles = articles.sortedBy { it.publishedAt }
            println("📊 전체 ${sortedArticles.size}개 기사를 시간순(FIFO)으로 정렬했습니다.")

            scannedCount = sortedArticles.size

            sortedArticles.forEach { article ->
                // 3. Duplicate Check
                if (!newsArticleRepository.existsByOriginalUrl(article.url)) {
                    
                    val isTwitter = article.sourceName.contains("X (", ignoreCase = true)

                    // 4. Pre-filtering (유료 버전이므로 넉넉하게 키워드 체크)
                    if (!isTwitter) {
                        val lowerTitle = article.title.lowercase()
                        val isPotentialNews = keywords.any { lowerTitle.contains(it) }

                        if (!isPotentialNews) {
                            println("⏭️ 1차 필터링 탈락 (키워드 없음): ${article.title.take(30)}...")
                            return@forEach
                        }
                    }

                    // 5. AI Processing
                    println("🤖 AI 분석 시작: ${article.title.take(40)}... (출처: ${article.sourceName})")
                    
                    // 마르카 기사인 경우 본문 및 기자 이름 추가 수집
                    val (fullContent, actualAuthor) = if (article.sourceName == "Marca") {
                        val detail = scraperService.fetchFullContent(article.url)
                        detail.content to detail.authorName
                    } else {
                        article.title to (article.authorName ?: "Unknown") // 트위터는 제목이 곧 본문
                    }

                    val geminiResult = geminiService.processArticle(article.title, fullContent, article.sourceName)
                    
                    if (geminiResult != null && (isTwitter || geminiResult.is_atm_related)) {
                        println("✅ 번역 성공! DB 저장 중: ${geminiResult.title_ko?.take(30)}...")
                        aiProcessedCount++

                        // 6. Save
                        newsArticleRepository.save(
                            NewsArticle(
                                articleType = article.articleType,
                                sourceName = article.sourceName,
                                authorName = actualAuthor, // 이제 실제 기자 이름이 들어갑니다.
                                originalUrl = article.url,
                                titleKo = geminiResult.title_ko,
                                summaryKo = geminiResult.summary_ko,
                                contentKo = geminiResult.content_ko,
                                contentRaw = fullContent, // 이제 여기에 원문 전체가 들어갑니다.
                                imageUrl = article.imageUrl,
                                isAtmRelated = true,
                                publishedAt = OffsetDateTime.now()
                            )
                        )
                    } else {
                        if (geminiResult == null) {
                            println("❌ AI 번역 실패: DB에 저장하지 않습니다. (건너뜀)")
                        } else {
                            println("⏭️ AI 필터링됨: 관련 없는 소식 (이유: ${geminiResult.reason})")
                        }
                    }

                } else {
                    println("⏭️ 중복 건너뜀: ${article.title.take(30)}...")
                }
            }

            saveLog(CollectionStatus.SUCCESS, scannedCount, aiProcessedCount, twitterApiCallCount, startTime)
        } catch (e: Exception) {
            saveLog(CollectionStatus.FAIL, scannedCount, aiProcessedCount, twitterApiCallCount, startTime, e.message)
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
