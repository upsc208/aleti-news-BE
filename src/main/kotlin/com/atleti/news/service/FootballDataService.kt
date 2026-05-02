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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
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

    private val SEARCH_QUERY = "Atletico Madrid"
    private val ATLETI_TEAM_ID = "9906"
    private var cachedResponse: MatchStatusResponse? = null

    suspend fun getRecentMatch(): RecentMatch? {
        if (cachedResponse == null || cachedResponse?.recentResult == null) loadFromDbOrUpdate()
        return cachedResponse?.recentResult
    }

    suspend fun getUpcomingMatches(): List<UpcomingMatch> {
        if (cachedResponse == null || (cachedResponse?.upcomingMatches?.isEmpty() == true)) loadFromDbOrUpdate()
        return cachedResponse?.upcomingMatches ?: emptyList()
    }

    private suspend fun loadFromDbOrUpdate() {
        // [Cleanup] 잘못 저장된 타 팀 경기 삭제
        val allResults = matchResultRepository.findAll()
        val wrongResults = allResults.filter { it.homeTeam.contains("Nacional") || it.awayTeam.contains("Nacional") }
        if (wrongResults.isNotEmpty()) {
            println("🧹 [Match DB] Removing ${wrongResults.size} incorrect match results from DB.")
            matchResultRepository.deleteAll(wrongResults)
        }

        // 1. 최근 경기 결과 로드
        val latestDbMatch = matchResultRepository.findTopByOrderByMatchDateDesc()
        var recentResult: RecentMatch? = null
        if (latestDbMatch != null) {
            val scorers = try {
                objectMapper.readValue(latestDbMatch.scorersJson, objectMapper.typeFactory.constructCollectionType(List::class.java, Scorer::class.java))
            } catch (e: Exception) { emptyList<Scorer>() }

            recentResult = RecentMatch(
                matchId = latestDbMatch.matchId,
                homeTeam = latestDbMatch.homeTeam,
                awayTeam = latestDbMatch.awayTeam,
                homeScore = latestDbMatch.homeScore,
                awayScore = latestDbMatch.awayScore,
                scorers = scorers,
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
        println("💾 [Match DB] Loaded from database. Result: ${recentResult?.homeTeam ?: "None"} / Upcoming: ${upcomingList.size}건")
        
        // 업데이트 여부 판단 (24시간 경과 또는 데이터 부재)
        val isResultOld = latestDbMatch == null || latestDbMatch.updatedAt.isBefore(LocalDateTime.now().minusHours(24))
        val isUpcomingMissing = upcomingList.isEmpty()
        
        if (isResultOld || isUpcomingMissing) {
            println("🔄 [Football API] Data is missing or old. Triggering API update...")
            updateMatchStatus()
        } else {
            println("✅ [Football API] DB data is fresh. Skipping API call.")
        }
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() = runBlocking {
        println("⏰ [Football API] Scheduled daily update started.")
        updateMatchStatus()
    }

    private suspend fun updateMatchStatus() {
        val startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
        val monthlyUsage = collectionLogRepository.sumFootballApiCallCountSince(startOfMonth) ?: 0
        if (monthlyUsage >= 95) {
            println("⚠️ [Football API] Monthly limit reached.")
            return
        }

        var apiCallCount = 0
        val startTime = System.currentTimeMillis()

        try {
            var newRecentMatch: RecentMatch? = null
            val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            
            // 1. 최근 경기 결과 수집
            for (i in 0..2) {
                val dateStr = LocalDate.now().minusDays(i.toLong()).format(dateFormatter)
                val url = "/football-get-matches-by-date?date=$dateStr"
                
                val rawResponse = try {
                    delay(2000)
                    webClient.get().uri(url).retrieve().bodyToMono(String::class.java).awaitSingle()
                } catch (e: Exception) { null }
                
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
                        
                        val isAtleti = hId == ATLETI_TEAM_ID || aId == ATLETI_TEAM_ID || 
                                     (hName.contains("atletico madrid") || aName.contains("atletico madrid")) ||
                                     ((hName == "atletico" || hName == "atleti") && !hName.contains("nacional") && !hName.contains("mineiro"))
                        
                        isAtleti && node.at("/status/finished").asBoolean()
                    }
                    
                    if (atletiMatch != null) {
                        val matchId = atletiMatch.at("/id").asText()
                        val scorers = try { apiCallCount++; fetchScorers(matchId) } catch (e: Exception) { emptyList() }
                        val finalHName = atletiMatch.at("/home/longName").asText().takeIf { it.isNotBlank() } ?: atletiMatch.at("/home/name").asText()
                        val finalAName = atletiMatch.at("/away/longName").asText().takeIf { it.isNotBlank() } ?: atletiMatch.at("/away/name").asText()

                        newRecentMatch = RecentMatch(
                            matchId = matchId,
                            homeTeam = finalHName,
                            awayTeam = finalAName,
                            homeScore = atletiMatch.at("/home/score").asInt(),
                            awayScore = atletiMatch.at("/away/score").asInt(),
                            scorers = scorers,
                            matchDate = atletiMatch.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: dateStr,
                            isHome = atletiMatch.at("/home/id").asText() == ATLETI_TEAM_ID || finalHName.lowercase().contains("atletico"),
                            tournament = atletiMatch.at("/leagueName").asText().takeIf { it.isNotBlank() } ?: "Tournament"
                        )

                        // Final Safety Check
                        if (newRecentMatch.homeTeam.contains("Madrid") || newRecentMatch.awayTeam.contains("Madrid") || atletiMatch.at("/home/id").asText() == ATLETI_TEAM_ID) {
                            matchResultRepository.save(MatchResult(
                                matchId = newRecentMatch.matchId,
                                homeTeam = newRecentMatch.homeTeam,
                                awayTeam = newRecentMatch.awayTeam,
                                homeScore = newRecentMatch.homeScore,
                                awayScore = newRecentMatch.awayScore,
                                scorersJson = objectMapper.writeValueAsString(scorers),
                                matchDate = newRecentMatch.matchDate,
                                tournament = newRecentMatch.tournament,
                                isHome = newRecentMatch.isHome
                            ))
                            break
                        }
                    }
                }
            }

            // 2. 향후 일정 수집 (Search)
            val upcomingMatches = mutableListOf<UpcomingMatch>()
            try {
                delay(2000)
                val rawSearchResponse = webClient.get().uri("/football-matches-search?search=$SEARCH_QUERY").retrieve().bodyToMono(String::class.java).awaitSingle()
                val searchResponse = objectMapper.readTree(rawSearchResponse)
                apiCallCount++
                
                val suggestions = searchResponse.path("response").path("suggestions")
                if (suggestions.isArray) {
                    val now = Instant.now()
                    val sortedUpcoming = suggestions.filter { node ->
                        val hName = node.path("homeTeamName").asText().lowercase()
                        val aName = node.path("awayTeamName").asText().lowercase()
                        val combinedName = hName + " " + aName
                        val isFinished = node.at("/status/finished").asBoolean()
                        val timeStr = node.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: node.path("matchDate").asText()
                        
                        val isAtleti = combinedName.contains("atletico") || combinedName.contains("atleti") || combinedName.contains("atm")
                        val isNotOther = !combinedName.contains("nacional") && !combinedName.contains("mineiro")
                        
                        isAtleti && isNotOther && !isFinished && try { Instant.parse(timeStr).isAfter(now.minusSeconds(3600)) } catch (e: Exception) { false }
                    }.sortedBy { it.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: it.path("matchDate").asText() }.take(3)
                    
                    // DB 저장 전 기존 일정 초기화
                    upcomingMatchRepository.deleteAll()
                    
                    for (node in sortedUpcoming) {
                        val hName = node.path("homeTeamName").asText()
                        val aName = node.path("awayTeamName").asText()
                        val isAtletiHome = hName.lowercase().contains("atletico") || hName.lowercase().contains("atleti")
                        val matchDateStr = node.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: node.path("matchDate").asText()
                        val tournamentStr = node.path("leagueName").asText().takeIf { it.isNotBlank() } ?: "Tournament"
                        
                        val entity = UpcomingMatchEntity(
                            opponent = if (isAtletiHome) aName else hName,
                            matchDate = matchDateStr,
                            isHome = isAtletiHome,
                            tournament = tournamentStr
                        )
                        upcomingMatchRepository.save(entity)
                        
                        upcomingMatches.add(UpcomingMatch(entity.opponent, entity.matchDate, entity.isHome, entity.tournament))
                    }
                }
            } catch (e: Exception) {
                println("⚠️ [Football API] Upcoming search failed: ${e.message}")
            }

            cachedResponse = MatchStatusResponse(newRecentMatch ?: cachedResponse?.recentResult, upcomingMatches)
            println("✅ [Football API] 업데이트 완료 (API 호출: ${apiCallCount}회, 향후 일정: ${upcomingMatches.size}건 저장됨)")
            saveFootballLog(CollectionStatus.SUCCESS, apiCallCount, startTime)
        } catch (e: Exception) {
            println("❌ [Football API] 실패: ${e.message}")
            saveFootballLog(CollectionStatus.FAIL, apiCallCount, startTime, e.message)
        }
    }

    private suspend fun fetchScorers(matchId: String): List<Scorer> {
        val response = try {
            webClient.get().uri("/football-get-match-event-detail-by-event-id?event_id=$matchId").retrieve().bodyToMono(JsonNode::class.java).awaitSingle()
        } catch (e: Exception) { return emptyList() }
        val events = response.at("/response/events").takeIf { !it.isMissingNode } ?: response.at("/data/events")
        val scorers = mutableListOf<Scorer>()
        if (events.isArray) {
            for (event in events) {
                if (event.path("type").asText().lowercase().contains("goal")) {
                    scorers.add(Scorer(name = event.at("/player/name").asText().takeIf { it.isNotBlank() } ?: event.path("playerName").asText(), minute = "${event.path("time").asInt()}'", isHome = event.path("isHome").asBoolean()))
                }
            }
        }
        return scorers
    }

    private fun saveFootballLog(status: CollectionStatus, callCount: Int, startTime: Long, errorMsg: String? = null) {
        collectionLogRepository.save(CollectionLog(status = status, footballApiCallCount = callCount, errorMsg = errorMsg, executionTimeMs = System.currentTimeMillis() - startTime))
    }
}

data class MatchStatusResponse(val recentResult: RecentMatch?, val upcomingMatches: List<UpcomingMatch>)
data class RecentMatch(val matchId: String, val homeTeam: String, val awayTeam: String, val homeScore: Int, val awayScore: Int, val scorers: List<Scorer>, val matchDate: String, val isHome: Boolean, val tournament: String)
data class Scorer(val name: String, val minute: String, val isHome: Boolean)
data class UpcomingMatch(val opponent: String, val matchDate: String, val isHome: Boolean, val tournament: String)
