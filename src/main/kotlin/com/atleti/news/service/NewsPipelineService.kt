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
    private val nitterScraperService: NitterScraperService,
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val collectionLogRepository: CollectionLogRepository
) {

    @Scheduled(fixedRate = 15 * 60 * 1000) // 15분마다 실행
    fun runPipeline() = runBlocking {
        var scannedCount = 0
        var aiProcessedCount = 0
        val startTime = System.currentTimeMillis()

        try {
            // 1. Scraping (Marca + Nitter)
            val articles = mutableListOf<ScrapedArticle>()
            articles.addAll(scraperService.scrapeMarcaAtm())

            // 1티어 기자들 리스트
            val journalists = listOf("MatteoMoretto", "FabrizioRomano")
            journalists.forEach { username ->
                articles.addAll(nitterScraperService.scrapeJournalist(username))
            }

            scannedCount = articles.size

            articles.forEach { article ->
                // 2. Duplicate Check
                if (!newsArticleRepository.existsByOriginalUrl(article.url)) {
                    // 3. AI Processing
                    val geminiResult = geminiService.processArticle(article.title, article.sourceName)
                    
                    if (geminiResult != null && geminiResult.is_atm_related) {
                        aiProcessedCount++
                        
                        // 4. Save
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

            saveLog(CollectionStatus.SUCCESS, scannedCount, aiProcessedCount, startTime)
        } catch (e: Exception) {
            saveLog(CollectionStatus.FAIL, scannedCount, aiProcessedCount, startTime, e.message)
        }
    }

    private fun saveLog(
        status: CollectionStatus,
        scanned: Int,
        aiProcessed: Int,
        startTime: Long,
        errorMsg: String? = null
    ) {
        collectionLogRepository.save(
            CollectionLog(
                status = status,
                scannedCount = scanned,
                aiProcessedCount = aiProcessed,
                errorMsg = errorMsg,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        )
    }
}
