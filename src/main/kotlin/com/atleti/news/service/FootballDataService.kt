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
        
        if (latestDbMatch == null || upcomingList.isEmpty()) {
            updateMatchStatus()
        }
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() = runBlocking {
        updateMatchStatus()
    }

    private suspend fun updateMatchStatus() {
        val startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
        val monthlyUsage = collectionLogRepository.sumFootballApiCallCountSince(startOfMonth) ?: 0
        if (monthlyUsage >= 95) return

        var apiCallCount = 0
        val startTime = System.currentTimeMillis()

        try {
            var newRecentMatch: RecentMatch? = null
            val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            
            // 1. 최근 경기 결과 수집 (Loop back to working version)
            for (i in 0..3) {
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
                        break
                    }
                }
            }

            // 2. 향후 일정 수집
            val upcomingMatches = mutableListOf<UpcomingMatch>()
            val rawSearchResponse = webClient.get().uri("/football-matches-search?search=$SEARCH_QUERY").retrieve().bodyToMono(String::class.java).awaitSingle()
            apiCallCount++
            
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

            cachedResponse = MatchStatusResponse(newRecentMatch ?: cachedResponse?.recentResult, upcomingMatches)
            saveFootballLog(CollectionStatus.SUCCESS, apiCallCount, startTime)
        } catch (e: Exception) {
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
