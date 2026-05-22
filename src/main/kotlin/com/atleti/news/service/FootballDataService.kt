package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.entity.MatchResult
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.MatchResultRepository
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant

@Service
class FootballDataService(
    @Value("\${rapidapi.key}") private val apiKey: String,
    @Value("\${atleti.news.enabled.football}") private val isFootballEnabled: Boolean,
    private val objectMapper: ObjectMapper,
    private val collectionLogRepository: CollectionLogRepository,
    private val matchResultRepository: MatchResultRepository,
    private val upcomingMatchRepository: UpcomingMatchRepository
) {
    private val apiHost = "soccer-football-info.p.rapidapi.com"
    private val webClient = WebClient.builder()
        .baseUrl("https://$apiHost")
        .exchangeStrategies(ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(20 * 1024 * 1024) }
            .build())
        .defaultHeader("X-RapidAPI-Key", apiKey)
        .defaultHeader("X-RapidAPI-Host", apiHost)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ATLETI_INFO_ID = "b9b29c2b44e9530c" // 사용자 확인 ID
    
    private var cachedResponse: MatchStatusResponse? = null
    private val updateMutex = Mutex()

    suspend fun getRecentMatch(): RecentMatch? {
        updateMatchStatus() // 무조건 업데이트 시도 (테스트용)
        return cachedResponse?.recentResult
    }

    suspend fun getUpcomingMatches(): List<UpcomingMatch> {
        return emptyList()
    }

    suspend fun syncUpdate(): String {
        return try {
            println("🛠️ [Debug] Inspecting RAW JSON from: /teams/view/?i=$ATLETI_INFO_ID")
            updateMatchStatus()
            "✅ 콘솔 로그를 확인해 주세요! JSON 데이터가 출력되었습니다."
        } catch (e: Exception) {
            "❌ 에러 발생: ${e.message}"
        }
    }

    private suspend fun loadFromDbOrUpdate() {
        updateMatchStatus()
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() {
        serviceScope.launch { updateMatchStatus() }
    }

    private suspend fun updateMatchStatus() {
        val startTime = System.currentTimeMillis()
        try {
            println("📡 [Soccer Info] 1단계: 경기 ID 추출 중...")
            val teamViewRaw = webClient.get()
                .uri("/teams/view/?i=$ATLETI_INFO_ID&l=en_US")
                .retrieve()
                .bodyToMono(String::class.java)
                .awaitSingle()

            val json = objectMapper.readTree(teamViewRaw)
            val matchId = json.at("/result/0/last_matches/0/id").asText()
            
            if (matchId.isBlank()) {
                println("⚠️ [Soccer Info] 최근 경기 ID를 찾지 못했습니다.")
                return
            }

            println("📡 [Soccer Info] 2단계: 상세 지표(득점자 등) 요청 시작 (ID: $matchId)")
            
            // 주소 끝의 슬래시 유무와 파라미터 형식을 정확히 맞춤
            val detailRaw = webClient.get()
                .uri { it.path("/matches/view/full/").queryParam("id", matchId).build() }
                .retrieve()
                .bodyToMono(String::class.java)
                .awaitSingle()

            val detailJson = objectMapper.readTree(detailRaw)
            val prettyDetail = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(detailJson)
            
            println("\n================ [DETAIL JSON START] ================")
            println(prettyDetail)
            println("================ [DETAIL JSON END]   ================\n")

            saveFootballLog(CollectionStatus.SUCCESS, 2, startTime)
        } catch (e: Exception) {
            println("❌ [Soccer Info] 상세 정보 가져오기 실패: ${e.message}")
            saveFootballLog(CollectionStatus.FAIL, 2, startTime, e.message)
        }
    }

    private fun saveFootballLog(status: CollectionStatus, callCount: Int, startTime: Long, errorMsg: String? = null) {
        collectionLogRepository.save(CollectionLog(status = status, footballApiCallCount = callCount, errorMsg = errorMsg, executionTimeMs = System.currentTimeMillis() - startTime))
    }
}

data class MatchStatusResponse(val recentResult: RecentMatch?, val upcomingMatches: List<UpcomingMatch>)
data class RecentMatch(val matchId: String, val homeTeam: String, val awayTeam: String, val homeScore: Int, val awayScore: Int, val matchDate: String, val isHome: Boolean, val tournament: String, val scorers: String? = null, val lineup: String? = null, val cards: String? = null, val possession: String? = null, val shotsOnTarget: String? = null, val xg: String? = null)
data class UpcomingMatch(val opponent: String, val matchDate: String, val isHome: Boolean, val tournament: String)
