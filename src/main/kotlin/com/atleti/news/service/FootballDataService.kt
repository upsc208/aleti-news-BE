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
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.http.HttpStatusCode
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
    @Value("\${rapidapi.daily.limit:33}") private val dailyLimit: Int,
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
    private var last429Time: Instant? = null

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
        
        // 데이터가 없으면 백그라운드에서 업데이트 시작 (서버 시작 시에는 간격 체크 생략)
        if (latestDbMatch == null || upcomingList.isEmpty()) {
            serviceScope.launch {
                requestUpdate(force = true)
            }
        }
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() {
        serviceScope.launch {
            requestUpdate(force = false)
        }
    }

    private suspend fun requestUpdate(force: Boolean = false) = updateMutex.withLock {
        val now = Instant.now()
        
        // 1. 429 Cooldown check (2 hours) - force여도 429는 존중 (차단 방지)
        last429Time?.let {
            if (it.isAfter(now.minus(2, java.time.temporal.ChronoUnit.HOURS))) {
                println("⏭️ [Football API] 429 에러로 인한 쿨다운 중입니다. (마지막 에러: $it)")
                return@withLock
            }
        }

        // 2. 24h interval check (Based on last SUCCESS log)
        if (!force) {
            val lastSuccessLog = collectionLogRepository.findAll().filter { it.status == CollectionStatus.SUCCESS && it.footballApiCallCount > 0 }.maxByOrNull { it.createdAt }
            val lastSuccessTime = lastSuccessLog?.createdAt?.toInstant()
            
            if (lastSuccessTime != null && lastSuccessTime.isAfter(now.minus(24, java.time.temporal.ChronoUnit.HOURS))) {
                println("⏭️ [Football API] 최근 24시간 내에 이미 성공적으로 업데이트했습니다. (마지막 성공: $lastSuccessTime)")
                return@withLock
            }
        } else {
            println("📡 [Football API] 서버 시작 또는 데이터 부재로 인해 즉시 업데이트를 시도합니다. (간격 체크 무시)")
        }

        updateMatchStatus()
    }

    private suspend fun updateMatchStatus() {
        if (!isFootballEnabled) {
            println("⏭️ [Football API] 수집이 설정에 의해 비활성화되어 있습니다.")
            return
        }

        val nowZoned = LocalDateTime.now().atOffset(java.time.ZoneOffset.ofHours(9))
        val startOfDay = nowZoned.toLocalDate().atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(9))
        val dailyUsage = collectionLogRepository.sumFootballApiCallCountSince(startOfDay) ?: 0
        if (dailyUsage >= dailyLimit) {
            println("⏭️ [Football API] 오늘 호출 한도(${dailyLimit}회)에 도달하여 수집을 중단합니다. (현재: $dailyUsage)")
            return
        }

        val startOfMonth = nowZoned.toLocalDate().withDayOfMonth(1).atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(9))
        val monthlyUsage = collectionLogRepository.sumFootballApiCallCountSince(startOfMonth) ?: 0
        if (monthlyUsage >= 95) {
            println("⏭️ [Football API] 이번 달 호출 한도(95회)에 도달하여 수집을 중단합니다. (현재: $monthlyUsage)")
            return
        }

        var apiCallCount = 0
        val startTime = System.currentTimeMillis()
        var hit429 = false

        try {
            println("📡 [Football API] 경기 정보 업데이트 시작...")
            var newRecentMatch: RecentMatch? = null
            val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            
            // 1. 최근 경기 결과 수집 (최근 3일)
            for (i in 0..3) {
                if (dailyUsage + apiCallCount >= dailyLimit) break
                
                val dateStr = LocalDate.now().minusDays(i.toLong()).format(dateFormatter)
                val url = "/football-get-matches-by-date?date=$dateStr"
                
                val rawResponse = try {
                    delay(4000) // 2초에서 4초로 지연 시간 증가 (Rate Limit 방지)
                    println("📡 [Football API] $dateStr 날짜 데이터 요청 중...")
                    webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(java.time.Duration.ofSeconds(15))
                        .awaitSingle()
                } catch (e: Exception) { 
                    if (e is CancellationException) throw e
                    val is429 = (e is WebClientResponseException && e.statusCode.value() == 429) || 
                                e.message?.contains("429") == true

                    println("⚠️ [Football API] $dateStr 요청 실패: ${e.message}")
                    if (is429) {
                        hit429 = true
                        last429Time = Instant.now()
                    }
                    null 
                }

                apiCallCount++
                if (hit429 || rawResponse == null) break

                val dateResponse = objectMapper.readTree(rawResponse)
                val matches = dateResponse.at("/response/matches")

                if (matches.isArray && matches.size() > 0) {
                    val atletiMatch = matches.find { node ->
                        val hName = (node.at("/home/longName").asText().takeIf { it.isNotBlank() } ?: node.at("/home/name").asText()).lowercase()
                        val aName = (node.at("/away/longName").asText().takeIf { it.isNotBlank() } ?: node.at("/away/name").asText()).lowercase()
                        val hId = node.at("/home/id").asText()
                        val aId = node.at("/away/id").asText()
                        val isFinished = node.at("/status/finished").asBoolean()

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
                            matchId = matchId, homeTeam = hName, awayTeam = aName,
                            homeScore = atletiMatch.at("/home/score").asInt(), awayScore = atletiMatch.at("/away/score").asInt(), matchDate = atletiMatch.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: dateStr,
                            isHome = atletiMatch.at("/home/id").asText() == ATLETI_TEAM_ID || hName.lowercase().contains("madrid"),
                            tournament = atletiMatch.at("/leagueName").asText().takeIf { it.isNotBlank() } ?: "Tournament"
                        )

                        try {
                            if (!matchResultRepository.existsByMatchId(matchId)) {
                                matchResultRepository.save(MatchResult(
                                    matchId = newRecentMatch.matchId, homeTeam = newRecentMatch.homeTeam, awayTeam = newRecentMatch.awayTeam,
                                    homeScore = newRecentMatch.homeScore, awayScore = newRecentMatch.awayScore, matchDate = newRecentMatch.matchDate,
                                    tournament = newRecentMatch.tournament, isHome = newRecentMatch.isHome
                                ))
                            }
                        } catch (e: DataIntegrityViolationException) { }
                        break
                    }
                }
                }

                // 2. 향후 일정 수집 (429 발생 안 했을 때만)
                val upcomingMatches = mutableListOf<UpcomingMatch>()
                if (!hit429 && (dailyUsage + apiCallCount < dailyLimit)) {
                val rawSearchResponse = try {
                    webClient.get()
                        .uri("/football-matches-search?search=$SEARCH_QUERY")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(java.time.Duration.ofSeconds(15))
                        .awaitSingle()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val is429 = (e is WebClientResponseException && e.statusCode.value() == 429) || 
                                e.message?.contains("429") == true

                    println("⚠️ [Football API] 검색 요청 실패: ${e.message}")
                    if (is429) {
                        hit429 = true
                        last429Time = Instant.now()
                    }
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
            }

            cachedResponse = MatchStatusResponse(newRecentMatch ?: cachedResponse?.recentResult, upcomingMatches)
            saveFootballLog(if (hit429) CollectionStatus.FAIL else CollectionStatus.SUCCESS, apiCallCount, startTime, if (hit429) "429 Too Many Requests" else null)
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
