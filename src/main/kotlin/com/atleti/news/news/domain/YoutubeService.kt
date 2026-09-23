package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.regex.Pattern
import com.atleti.news.news.external.GeminiService

@Service
class YoutubeService(
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val articleDeletionService: ArticleDeletionService,
    @Value("\${serpapi.key}") private val serpApiKey: String
) {
    private val log = LoggerFactory.getLogger(YoutubeService::class.java)

    // 3시간 분량 자막은 기본 버퍼 크기(256KB)를 초과하므로 제한을 늘림 (약 10MB)
    private val webClient = WebClient.builder()
        .exchangeStrategies(ExchangeStrategies.builder()
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)
            }
            .build())
        .build()

    suspend fun runYoutubePipeline(channelId: String = "@rubenuriagonzalez") {
        if (serpApiKey == "NO_KEY" || serpApiKey.isBlank()) {
            log.warn("⚠️ SERPAPI_KEY가 설정되지 않았습니다.")
            return
        }

        // 1. 무료 스크래핑으로 최신 라이브 영상 ID와 상대적 날짜 확인 (쿼터 소모 0)
        val videoInfo = fetchLatestLiveVideoInfo(channelId) ?: return
        val videoId = videoInfo.first
        val relativeDate = videoInfo.second
        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        // 2. DB 중복 체크
        if (newsArticleRepository.existsByOriginalUrl(videoUrl)) {
            return
        }
        if (articleDeletionService.isDeleted(videoUrl)) {
            return
        }

        log.info("🚀 새 라이브 방송 발견! 트랜스크립트 추출 시작: $videoUrl ($relativeDate)")
        
        // 3. 진짜 새 영상일 때만 SerpApi 호출 (자막 추출용 쿼터 1회 소모)
        val transcript = fetchTranscript(videoId)
        if (transcript.isNullOrBlank()) {
            log.warn("⚠️ 트랜스크립트가 비어있거나 아직 생성되지 않았습니다.")
            return
        }

        // 4. 스페인 시간 기준 날짜 계산
        val calculatedTime = parseToSpainTime(relativeDate, Instant.now())
        val spainDateStr = "${calculatedTime.monthValue}월 ${calculatedTime.dayOfMonth}일"
        
        log.info("🤖 Gemini 요약 및 번역 시작... (스페인 날짜 기준: $spainDateStr)")
        val youtubeResult = geminiService.processYoutubeTranscript(transcript, "YouTube: Ruben Uria", spainDateStr)
        
        if (youtubeResult != null && youtubeResult.isAtmRelated) {
            val article = NewsArticle(
                articleType = ArticleType.YOUTUBE,
                sourceName = "YouTube",
                authorName = "Ruben Uria",
                originalUrl = videoUrl,
                titleKo = youtubeResult.titleKo,
                summaryKo = youtubeResult.summaryKo,
                contentKo = youtubeResult.contentKo,
                contentRaw = transcript.take(2000),
                imageUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                category = "라이브요약",
                isAtmRelated = true,
                publishedAt = calculatedTime
            )
            newsArticleRepository.save(article)
            log.info("✅ 라이브 요약 리포트 저장 완료: ${youtubeResult.titleKo}")
        }
    }

    /**
     * 라이브 스트림 탭을 긁는다 (무료 — SerpApi 쿼터를 안 씀).
     */
    private suspend fun fetchLatestLiveVideoInfo(channelId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect("https://www.youtube.com/$channelId/streams")
                .userAgent(USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
            parseLatestVideo(doc.html())
        } catch (e: Exception) {
            log.error("❌ 유튜브 라이브 탭 스크래핑 실패: ${e.message}", e)
            null
        }
    }

    /**
     * 자막을 받아 옴
     */
    private suspend fun fetchTranscript(videoId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://serpapi.com/search.json?engine=youtube_video_transcript&v=$videoId&api_key=$serpApiKey"
        try {
            withTimeout(TRANSCRIPT_TIMEOUT_MS) {
                joinTranscript(webClient.get().uri(url).retrieve().awaitBody<Map<String, Any>>())
            }
        } catch (e: TimeoutCancellationException) {
            log.error("❌ 트랜스크립트 응답 시간 초과 (${TRANSCRIPT_TIMEOUT_MS}ms)")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("❌ 트랜스크립트 추출 실패: ${hideKey(e.message)}")
            null
        }
    }

    private fun hideKey(message: String?): String =
        message.orEmpty().replace(serpApiKey, "***")

    companion object {
        private const val TRANSCRIPT_TIMEOUT_MS = 60_000L

        internal const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private val ID_PATTERN: Pattern = Pattern.compile("\"videoId\":\"([^\"]+)\"")
        private val DATE_PATTERN: Pattern =
            Pattern.compile("\"publishedTimeText\":\\{\"simpleText\":\"([^\"]+)\"\\}")

        private val SPAIN: ZoneId = ZoneId.of("Europe/Madrid")

        internal fun parseLatestVideo(html: String): Pair<String, String>? {
            val idMatcher = ID_PATTERN.matcher(html)
            if (!idMatcher.find()) return null
            val dateMatcher = DATE_PATTERN.matcher(html)
            val relativeDate = if (dateMatcher.find()) dateMatcher.group(1) else "0 minutes ago"
            return idMatcher.group(1) to relativeDate
        }

        internal fun parseToSpainTime(relative: String, now: Instant): OffsetDateTime {
            val number = Regex("\\d+").find(relative)?.value?.toLong() ?: 0L

            val absolute = when {
                relative.contains("hour") || relative.contains("시간") -> now.minus(Duration.ofHours(number))
                relative.contains("week") || relative.contains("주") -> now.minus(Duration.ofDays(number * 7))
                relative.contains("day") || relative.contains("일") -> now.minus(Duration.ofDays(number))
                relative.contains("minute") || relative.contains("분") -> now.minus(Duration.ofMinutes(number))
                else -> now
            }
            return absolute.atZone(SPAIN).toOffsetDateTime()
        }

        @Suppress("UNCHECKED_CAST")
        internal fun joinTranscript(response: Map<String, Any>): String? =
            (response["transcript"] as? List<Map<String, Any>>)
                ?.mapNotNull { it["snippet"] as? String }
                ?.joinToString(" ")
    }
}
