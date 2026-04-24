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
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val collectionLogRepository: CollectionLogRepository
) {

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

            // 2. Scraping (Twitter via RapidAPI)
            // 1티어 기자들 및 ITK 리스트
            val journalists = listOf("MatteoMoretto", "FabrizioRomano", "David_Medina", "rubenuria")
            
            for (username in journalists) {
                // 오늘 남은 횟수가 있는지 확인
                if (twitterApiService.getTodayApiUsage() + twitterApiCallCount < 45) {
                    val tweets = twitterApiService.getTweets(username)
                    if (tweets.isNotEmpty()) {
                        articles.addAll(tweets)
                        twitterApiCallCount++
                    }
                    // 429 오류 방지를 위한 2초 대기
                    kotlinx.coroutines.delay(2000)
                } else {
                    println("⏭️ 오늘 Twitter API 호출 제한에 도달하여 @\$username 수집을 건너뜁니다.")
                }
            }

            scannedCount = articles.size

            articles.forEach { article ->
                // 3. Duplicate Check
                if (!newsArticleRepository.existsByOriginalUrl(article.url)) {
                    // 4. AI Processing
                    val geminiResult = geminiService.processArticle(article.title, article.sourceName)
                    
                    if (geminiResult != null && geminiResult.is_atm_related) {
                        aiProcessedCount++
                        
                        // 5. Save
                        newsArticleRepository.save(
                            NewsArticle(
                                sourceName = article.sourceName,
                                originalUrl = article.url,
                                titleKo = geminiResult.title_ko ?: article.title,
                                summaryKo = geminiResult.summary_ko,
                                imageUrl = article.imageUrl,
                                isAtmRelated = true,
                                publishedAt = OffsetDateTime.now()
                            )
                        )
                    }
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
