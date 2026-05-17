package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.entity.MatchResult
import com.atleti.news.entity.UpcomingMatchEntity
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.MatchResultRepository
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class FootballDataService(
    @Value("\${rapidapi.key}") private val apiKey: String,
    @Value("\${rapidapi.football.host}") private val apiHost: String,
    @Value("\${atleti.news.enabled.football}") private val isFootballEnabled: Boolean,
    private val objectMapper: ObjectMapper,
    private val collectionLogRepository: CollectionLogRepository,
    private val matchResultRepository: MatchResultRepository,
    private val upcomingMatchRepository: UpcomingMatchRepository
) {
    private val webClient = WebClient.builder()
        .baseUrl("https://$apiHost")
        .exchangeStrategies(ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .build())
        .defaultHeader("X-RapidAPI-Key", apiKey)
        .defaultHeader("X-RapidAPI-Host", apiHost)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val SEARCH_QUERY = "Atletico Madrid"
    private val ATLETI_TEAM_ID = "9906"
    private var cachedResponse: MatchStatusResponse? = null
    private val updateMutex = Mutex()
    private var lastUpdateAttemptTime: Instant? = null

    suspend fun getRecentMatch(): RecentMatch? {
        if (cachedResponse == null || cachedResponse?.recentResult == null) loadFromDbOrUpdate()
        return cachedResponse?.recentResult
    }

    suspend fun getUpcomingMatches(): List<UpcomingMatch> {
        if (cachedResponse == null || (cachedResponse?.upcomingMatches?.isEmpty() == true)) loadFromDbOrUpdate()
        return cachedResponse?.upcomingMatches ?: emptyList()
    }

    private suspend fun loadFromDbOrUpdate() = updateMutex.withLock {
        // Double-check cache inside lock
        if (cachedResponse != null && cachedResponse?.recentResult != null && cachedResponse?.upcomingMatches?.isNotEmpty() == true) return@withLock

        println("🧹 [Match DB] Consistency Check Started.")
        
        // 1. 최근 경기 결과 로드
        val latestDbMatch = matchResultRepository.findTopByOrderByMatchDateDesc()
        var recentResult: RecentMatch? = null
        if (latestDbMatch != null) {
            recentResult = RecentMatch(
                matchId = latestDbMatch.matchId,
                homeTeam = latestDbMatch.homeTeam,
                awayTeam = latestDbMatch.awayTeam,
                homeScore = latestDbMatch.homeScore,
                awayScore = latestDbMatch.awayScore,
                matchDate = latestDbMatch.matchDate,
                isHome = latestDbMatch.isHome,
                tournament = latestDbMatch.tournament
            )
        }

        // 2. 향후 일정 로드
        val dbUpcoming = upcomingMatchRepository.findAllByOrderByMatchDateAsc()
        val upcomingList = dbUpcoming.map { 
            UpcomingMatch(it.opponent, it.matchDate, it.isHome, it.tournament)
        }

        cachedResponse = MatchStatusResponse(recentResult, upcomingList)
        
        // 데이터가 없으면 백그라운드에서 업데이트 시작
        if (latestDbMatch == null || upcomingList.isEmpty()) {
            val now = Instant.now()
            val oneDayAgo = now.minus(24, java.time.temporal.ChronoUnit.HOURS)
            
            if (lastUpdateAttemptTime == null || lastUpdateAttemptTime!!.isBefore(oneDayAgo)) {
                println("📡 [Football API] 데이터가 없으므로 업데이트를 시도합니다. (마지막 시도: $lastUpdateAttemptTime)")
                lastUpdateAttemptTime = now
                serviceScope.launch {
                    updateMutex.withLock {
                        updateMatchStatus()
                    }
                }
            } else {
                println("⏭️ [Football API] 최근 24시간 내에 이미 업데이트를 시도했습니다. 호출을 건너뜜 (마지막 시도: $lastUpdateAttemptTime)")
            }
        }
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() {
        serviceScope.launch {
            updateMutex.withLock {
                lastUpdateAttemptTime = Instant.now()
                updateMatchStatus()
            }
        }
    }

    private suspend fun updateMatchStatus() {
        if (!isFootballEnabled) {
            println("⏭️ [Football API] 수집이 설정에 의해 비활성화되어 있습니다.")
            return
        }

        val startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
        val monthlyUsage = collectionLogRepository.sumFootballApiCallCountSince(startOfMonth) ?: 0
        if (monthlyUsage >= 95) {
            println("⏭️ [Football API] 이번 달 호출 한도(95회)에 도달하여 수집을 중단합니다.")
            return
        }

        var apiCallCount = 0
        val startTime = System.currentTimeMillis()

        try {
            println("📡 [Football API] 경기 정보 업데이트 시작...")
            var newRecentMatch: RecentMatch? = null
            val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            
            // 1. 최근 경기 결과 수집 (API 쿼터 절약을 위해 최근 3일만 수집)
            for (i in 0..3) {
                val dateStr = LocalDate.now().minusDays(i.toLong()).format(dateFormatter)
                val url = "/football-get-matches-by-date?date=$dateStr"
                
                val rawResponse = try {
                    delay(2000)
                    println("📡 [Football API] $dateStr 날짜 데이터 요청 중...")
                    webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(java.time.Duration.ofSeconds(15))
                        .awaitSingle()
                } catch (e: Exception) { 
                    if (e is CancellationException) throw e
                    println("⚠️ [Football API] $dateStr 요청 실패: ${e.message}")
                    null 
                }
                
                apiCallCount++
                if (rawResponse == null) continue
                
                val dateResponse = objectMapper.readTree(rawResponse)
                val matches = dateResponse.at("/response/matches")

                if (matches.isArray && matches.size() > 0) {
                    val atletiMatch = matches.find { node ->
                        val hName = (node.at("/home/longName").asText().takeIf { it.isNotBlank() } ?: node.at("/home/name").asText()).lowercase()
                        val aName = (node.at("/away/longName").asText().takeIf { it.isNotBlank() } ?: node.at("/away/name").asText()).lowercase()
                        val hId = node.at("/home/id").asText()
                        val aId = node.at("/away/id").asText()
                        val isFinished = node.at("/status/finished").asBoolean()
                        
                        // 다시 엄격한 조건으로 복구 (ID 기반 또는 정확한 팀명 포함)
                        val isRealAtleti = (hId == ATLETI_TEAM_ID || aId == ATLETI_TEAM_ID) || 
                                         ((hName.contains("atletico") || hName.contains("atlético")) && hName.contains("madrid")) ||
                                         ((aName.contains("atletico") || aName.contains("atlético")) && aName.contains("madrid")) ||
                                         (hName == "atleti" || aName == "atleti")
                        
                        val isNotOther = !hName.contains("(w)") && !aName.contains("(w)") && 
                                       !hName.contains("u19") && !aName.contains("u19") &&
                                       !hName.contains("escobar") && !aName.contains("escobar")
                        
                        isRealAtleti && isNotOther && isFinished
                    }
                    
                    if (atletiMatch != null) {
                        println("✅ [Football API] 아틀레티코 마드리드 경기 발견! (${atletiMatch.at("/home/name").asText()} vs ${atletiMatch.at("/away/name").asText()})")
                        val matchId = atletiMatch.at("/id").asText()
                        val hName = atletiMatch.at("/home/longName").asText().takeIf { it.isNotBlank() } ?: atletiMatch.at("/home/name").asText()
                        val aName = atletiMatch.at("/away/longName").asText().takeIf { it.isNotBlank() } ?: atletiMatch.at("/away/name").asText()

                        newRecentMatch = RecentMatch(
                            matchId = matchId,
                            homeTeam = hName,
                            awayTeam = aName,
                            homeScore = atletiMatch.at("/home/score").asInt(),
                            awayScore = atletiMatch.at("/away/score").asInt(),
                            matchDate = atletiMatch.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: dateStr,
                            isHome = atletiMatch.at("/home/id").asText() == ATLETI_TEAM_ID || hName.lowercase().contains("madrid"),
                            tournament = atletiMatch.at("/leagueName").asText().takeIf { it.isNotBlank() } ?: "Tournament"
                        )

                        try {
                            if (!matchResultRepository.existsByMatchId(matchId)) {
                                matchResultRepository.save(MatchResult(
                                    matchId = newRecentMatch.matchId,
                                    homeTeam = newRecentMatch.homeTeam,
                                    awayTeam = newRecentMatch.awayTeam,
                                    homeScore = newRecentMatch.homeScore,
                                    awayScore = newRecentMatch.awayScore,
                                    matchDate = newRecentMatch.matchDate,
                                    tournament = newRecentMatch.tournament,
                                    isHome = newRecentMatch.isHome
                                ))
                            }
                        } catch (e: DataIntegrityViolationException) {
                            println("⚠️ [Match DB] Duplicate matchId detected ($matchId), skipping save.")
                        }
                        break
                    }
                }
            }

            // 2. 향후 일정 수집
            val upcomingMatches = mutableListOf<UpcomingMatch>()
            val rawSearchResponse = try {
                webClient.get()
                    .uri("/football-matches-search?search=$SEARCH_QUERY")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .awaitSingle()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            apiCallCount++
            
            if (rawSearchResponse != null) {
                val searchResponse = objectMapper.readTree(rawSearchResponse)
                val suggestions = searchResponse.path("response").path("suggestions")
                if (suggestions.isArray) {
                    val now = Instant.now()
                    val sortedUpcoming = suggestions.filter { node ->
                        val hName = node.path("homeTeamName").asText().lowercase()
                        val aName = node.path("awayTeamName").asText().lowercase()
                        val isFinished = node.at("/status/finished").asBoolean()
                        val timeStr = node.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: node.path("matchDate").asText()
                        
                        val isRealAtleti = ((hName.contains("atletico") || hName.contains("atlético")) && hName.contains("madrid")) ||
                                         ((aName.contains("atletico") || aName.contains("atlético")) && aName.contains("madrid")) ||
                                         (hName == "atleti" || aName == "atleti")
                        
                        val isNotOther = !hName.contains("(w)") && !aName.contains("(w)") && 
                                       !hName.contains("u19") && !aName.contains("u19") &&
                                       !hName.contains("escobar") && !aName.contains("escobar")

                        val isFuture = try { Instant.parse(timeStr.replace(" ", "T")).isAfter(now.minusSeconds(3600)) } catch (e: Exception) { true }
                        
                        isRealAtleti && isNotOther && !isFinished && isFuture
                    }.sortedBy { it.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: it.path("matchDate").asText() }.take(3)
                    
                    if (sortedUpcoming.isNotEmpty()) {
                        upcomingMatchRepository.deleteAll()
                        for (node in sortedUpcoming) {
                            val hName = node.path("homeTeamName").asText()
                            val aName = node.path("awayTeamName").asText()
                            val isAtletiHome = hName.lowercase().contains("madrid") || hName.lowercase() == "atleti"
                            
                            val entity = UpcomingMatchEntity(
                                opponent = if (isAtletiHome) aName else hName,
                                matchDate = node.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: node.path("matchDate").asText(),
                                isHome = isAtletiHome,
                                tournament = node.path("leagueName").asText().takeIf { it.isNotBlank() } ?: "Tournament"
                            )
                            upcomingMatchRepository.save(entity)
                            upcomingMatches.add(UpcomingMatch(entity.opponent, entity.matchDate, entity.isHome, entity.tournament))
                        }
                    }
                }
            }

            cachedResponse = MatchStatusResponse(newRecentMatch ?: cachedResponse?.recentResult, upcomingMatches)
            saveFootballLog(CollectionStatus.SUCCESS, apiCallCount, startTime)
        } catch (e: Exception) {
            println("❌ [Football API] Update failed: ${e.message}")
            saveFootballLog(CollectionStatus.FAIL, apiCallCount, startTime, e.message)
        }
    }

    private fun saveFootballLog(status: CollectionStatus, callCount: Int, startTime: Long, errorMsg: String? = null) {
        collectionLogRepository.save(CollectionLog(status = status, footballApiCallCount = callCount, errorMsg = errorMsg, executionTimeMs = System.currentTimeMillis() - startTime))
    }
}

data class MatchStatusResponse(val recentResult: RecentMatch?, val upcomingMatches: List<UpcomingMatch>)
data class RecentMatch(val matchId: String, val homeTeam: String, val awayTeam: String, val homeScore: Int, val awayScore: Int, val matchDate: String, val isHome: Boolean, val tournament: String)
data class UpcomingMatch(val opponent: String, val matchDate: String, val isHome: Boolean, val tournament: String)
