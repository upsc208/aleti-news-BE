package com.atleti.news.news.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @Value("\${serpapi.key}") private val serpApiKey: String
) {
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
            println("⚠️ SERPAPI_KEY가 설정되지 않았습니다.")
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

        println("🚀 새 라이브 방송 발견! 트랜스크립트 추출 시작: $videoUrl ($relativeDate)")
        
        // 3. 진짜 새 영상일 때만 SerpApi 호출 (자막 추출용 쿼터 1회 소모)
        val transcript = fetchTranscript(videoId)
        if (transcript.isNullOrBlank()) {
            println("⚠️ 트랜스크립트가 비어있거나 아직 생성되지 않았습니다.")
            return
        }

        // 4. 스페인 시간 기준 날짜 계산
        val calculatedTime = parseToSpainTime(relativeDate)
        val spainDateStr = "${calculatedTime.monthValue}월 ${calculatedTime.dayOfMonth}일"
        
        println("🤖 Gemini 요약 및 번역 시작... (스페인 날짜 기준: $spainDateStr)")
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
            println("✅ 라이브 요약 리포트 저장 완료: ${youtubeResult.titleKo}")
        }
    }

    private suspend fun fetchLatestLiveVideoInfo(channelId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            // 라이브 스트림 탭을 직접 긁음 (무료)
            val url = "https://www.youtube.com/$channelId/streams"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
            
            val html = doc.html()
            
            // 비디오 ID와 상대적 발행일 추출
            val idPattern = Pattern.compile("\"videoId\":\"([^\"]+)\"")
            val datePattern = Pattern.compile("\"publishedTimeText\":\\{\"simpleText\":\"([^\"]+)\"\\}")
            
            val idMatcher = idPattern.matcher(html)
            val dateMatcher = datePattern.matcher(html)
            
            if (idMatcher.find()) {
                val videoId = idMatcher.group(1)
                val relativeDate = if (dateMatcher.find()) dateMatcher.group(1) else "0 minutes ago"
                videoId to relativeDate
            } else null
        } catch (e: Exception) {
            println("❌ 유튜브 라이브 탭 스크래핑 실패: ${e.message}")
            null
        }
    }

    private fun parseToSpainTime(relative: String): OffsetDateTime {
        val nowUtc = Instant.now()
        val number = Regex("\\d+").find(relative)?.value?.toLong() ?: 0L
        
        val absoluteInstant = when {
            relative.contains("hour") || relative.contains("시간") -> nowUtc.minus(Duration.ofHours(number))
            relative.contains("day") || relative.contains("일") -> nowUtc.minus(Duration.ofDays(number))
            relative.contains("minute") || relative.contains("분") -> nowUtc.minus(Duration.ofMinutes(number))
            relative.contains("week") || relative.contains("주") -> nowUtc.minus(Duration.ofDays(number * 7))
            else -> nowUtc
        }

        val spainZone = ZoneId.of("Europe/Madrid")
        return absoluteInstant.atZone(spainZone).toOffsetDateTime()
    }

    private suspend fun fetchTranscript(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://serpapi.com/search.json?engine=youtube_video_transcript&v=$videoId&api_key=$serpApiKey"
            val response = webClient.get()
                .uri(url)
                .retrieve()
                .awaitBody<Map<String, Any>>()

            @Suppress("UNCHECKED_CAST")
            val transcriptArray = response["transcript"] as? List<Map<String, Any>>
            transcriptArray?.joinToString(" ") { it["snippet"] as String }
        } catch (e: Exception) {
            println("❌ 트랜스크립트 추출 실패: ${e.message}")
            null
        }
    }
}
