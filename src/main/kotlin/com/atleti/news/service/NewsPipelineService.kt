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
    private val collectionLogRepository: CollectionLogRepository
) {

    @Scheduled(fixedRate = 15 * 60 * 1000) // 15분마다 실행
    fun runPipeline() = runBlocking {
        var scannedCount = 0
        var aiProcessedCount = 0
        var twitterApiCallCount = 0
        val startTime = System.currentTimeMillis()

        try {
            // 1. Scraping (Marca) - 테스트를 위해 잠시 중단
            val articles = mutableListOf<ScrapedArticle>()
            // articles.addAll(scraperService.scrapeMarcaAtm())
            println("ℹ️ Marca 수집은 테스트를 위해 비활성화되었습니다.")

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
                        } else {
                            println("ℹ️ [RapidAPI] @$username 에서 가져온 트윗이 없습니다.")
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
                    
                    val isTwitter = article.sourceName.startsWith("X (@")

                    // 4. Pre-filtering (X 기사는 전담 기자이므로 필터링 생략)
                    if (!isTwitter) {
                        val keywords = listOf("atleti", "atletico", "simeone", "cholo", "rojiblanco", "metropolitano", "atm", "fichaje", "mercado")
                        val lowerTitle = article.title.lowercase()
                        val isPotentialNews = keywords.any { lowerTitle.contains(it) } || 
                                              article.sourceName.contains("Marca")

                        if (!isPotentialNews) {
                            println("⏭️ 1차 필터링 탈락 (키워드 없음): ${article.title.take(30)}...")
                            return@forEach
                        }
                    }

                    // 5. AI Processing
                    println("🤖 AI 분석 시작: ${article.title.take(40)}... (출처: ${article.sourceName})")
                    val geminiResult = geminiService.processArticle(article.title, article.sourceName)
                    
                    if (geminiResult == null && !isTwitter) {
                        println("❌ AI 처리 오류: Gemini로부터 응답을 받지 못했습니다. (건너뜀)")
                        return@forEach
                    }

                    // X 기사는 전담 기자들이므로 AI의 관련성 판별 결과나 AI 성공 여부와 무관하게 저장 시도
                    if (isTwitter || (geminiResult != null && geminiResult.is_atm_related)) {
                        val finalTitleKo = geminiResult?.title_ko ?: "뉴스 제목 (번역 대기 중)"
                        val finalSummaryKo = geminiResult?.summary_ko ?: "핵심 요약 생성 실패" 
                        val finalContentKo = geminiResult?.content_ko ?: "본문 번역 중 오류가 발생했습니다."

                        println("✅ DB 저장 중: ${finalTitleKo.take(30)}...")
                        aiProcessedCount++

                        // 6. Save
                        newsArticleRepository.save(
                            NewsArticle(
                                sourceName = article.sourceName,
                                originalUrl = article.url,
                                titleKo = finalTitleKo,
                                summaryKo = finalSummaryKo,
                                contentKo = finalContentKo, // AI가 준 한국어 번역본만 저장
                                contentRaw = article.title, // 영어 원문은 여기에만 저장
                                imageUrl = article.imageUrl,
                                isAtmRelated = true,
                                publishedAt = OffsetDateTime.now()
                            )
                        )
                    }
 else {
                        println("⏭️ AI 필터링됨: 관련 없는 소식 (이유: ${geminiResult?.reason})")
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
