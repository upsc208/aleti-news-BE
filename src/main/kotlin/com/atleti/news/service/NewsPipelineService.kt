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

    @Scheduled(fixedRate = 15 * 60 * 1000) // 15분마다 실행
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
                println("🔍 Twitter(RapidAPI) 수집 시도 중: @$username")

                val currentUsage = twitterApiService.getTodayApiUsage()
                if (currentUsage + twitterApiCallCount < 45) {
                    try {
                        val tweets = twitterApiService.getTweets(username)
                        if (tweets.isNotEmpty()) {
                            articles.addAll(tweets)
                            twitterApiCallCount++
                            println("✅ [RapidAPI] @$username 에서 ${tweets.size}개의 트윗을 가져왔습니다.")
                        }
                    } catch (e: Exception) {
                        println("❌ [RapidAPI] @$username 수집 중 오류: ${e.message}")
                    }
                } else {
                    println("⏭️ 오늘 호출 제한 도달: @$username 스킵")
                }

                kotlinx.coroutines.delay(2000)
            }


            scannedCount = articles.size

            articles.forEach { article ->
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
                    val geminiResult = geminiService.processArticle(article.title, article.sourceName)
                    
                    if (geminiResult != null && (isTwitter || geminiResult.is_atm_related)) {
                        println("✅ 번역 성공! DB 저장 중: ${geminiResult.title_ko?.take(30)}...")
                        aiProcessedCount++

                        // 6. Save
                        newsArticleRepository.save(
                            NewsArticle(
                                articleType = article.articleType,
                                sourceName = article.sourceName,
                                authorName = article.authorName,
                                originalUrl = article.url,
                                titleKo = geminiResult.title_ko,
                                summaryKo = geminiResult.summary_ko,
                                contentKo = geminiResult.content_ko,
                                contentRaw = article.title,
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
