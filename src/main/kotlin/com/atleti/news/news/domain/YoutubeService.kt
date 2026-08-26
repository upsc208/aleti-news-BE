package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
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
     * 라이브 스트림 탭을 긁는다 (무료 — SerpApi 쿼터를 안 쓴다).
     * **네트워크만 담당하고 해석은 [parseLatestVideo]에 맡긴다.**
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
     * 자막을 받아 온다. **네트워크만 담당하고 조립은 [joinTranscript]에 맡긴다.**
     * 여기서만 SerpApi 쿼터를 1회 쓴다 — 그래서 중복 체크를 통과한 뒤에 부른다.
     */
    private suspend fun fetchTranscript(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://serpapi.com/search.json?engine=youtube_video_transcript&v=$videoId&api_key=$serpApiKey"
            joinTranscript(webClient.get().uri(url).retrieve().awaitBody<Map<String, Any>>())
        } catch (e: Exception) {
            log.error("❌ 트랜스크립트 추출 실패: ${e.message}", e)
            null
        }
    }

    companion object {
        internal const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private val ID_PATTERN: Pattern = Pattern.compile("\"videoId\":\"([^\"]+)\"")
        private val DATE_PATTERN: Pattern =
            Pattern.compile("\"publishedTimeText\":\\{\"simpleText\":\"([^\"]+)\"\\}")

        private val SPAIN: ZoneId = ZoneId.of("Europe/Madrid")

        /**
         * 스트림 탭 HTML에서 **가장 위(=최신) 영상**의 ID와 상대 발행일을 뽑는다. 순수 함수다.
         *
         * 정규식이 처음 찾는 것을 쓴다 — 유튜브가 최신순으로 그려 주기 때문이다.
         * 발행일을 못 찾으면 `"0 minutes ago"`로 둔다. 그러면 [parseToSpainTime]이 지금 시각으로
         * 계산하는데, 방금 올라온 영상일 가능성이 제일 높으니 그 편이 낫다.
         */
        internal fun parseLatestVideo(html: String): Pair<String, String>? {
            val idMatcher = ID_PATTERN.matcher(html)
            if (!idMatcher.find()) return null
            val dateMatcher = DATE_PATTERN.matcher(html)
            val relativeDate = if (dateMatcher.find()) dateMatcher.group(1) else "0 minutes ago"
            return idMatcher.group(1) to relativeDate
        }

        /**
         * `"3 hours ago"` · `"2일 전"` 같은 상대 표기를 실제 시각으로 되돌려 스페인 시간대로 준다.
         * 순수 함수다 — [now]를 받으므로 같은 입력이면 항상 같은 결과다.
         *
         * **판정 순서가 곧 우선순위다.** 한국어 표기는 서로 글자를 품는다 — `"주일"`에는 `"일"`이,
         * 분 단위 표기에는 숫자가 붙는다. 아래 순서를 바꾸면 `"1주일 전"`이 하루 전으로 읽힌다.
         * 유튜브 한국어는 보통 `"1주 전"`으로 주지만, 순서에 기대고 있다는 점은 남겨 둔다.
         *
         * 모르는 표기(`"1개월 전"` 등)는 [now] 그대로다. 이 파이프라인은 **최신 영상 하나**만
         * 보므로 한 달 전 영상이 걸릴 일이 사실상 없고, 지어낸 과거 시각보다 낫다.
         */
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

        /** SerpApi 응답의 자막 조각을 한 문자열로 잇는다. 조각이 없으면 null */
        @Suppress("UNCHECKED_CAST")
        internal fun joinTranscript(response: Map<String, Any>): String? =
            (response["transcript"] as? List<Map<String, Any>>)
                ?.mapNotNull { it["snippet"] as? String }
                ?.joinToString(" ")
    }
}
