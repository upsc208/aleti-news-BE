package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.repository.CollectionLogRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class FootballDataService(
    @Value("\${rapidapi.key}") private val apiKey: String,
    @Value("\${rapidapi.football.host}") private val apiHost: String,
    private val objectMapper: ObjectMapper,
    private val collectionLogRepository: CollectionLogRepository
) {
    private val webClient = WebClient.builder()
        .baseUrl("https://$apiHost")
        .defaultHeader("X-RapidAPI-Key", apiKey)
        .defaultHeader("X-RapidAPI-Host", apiHost)
        .build()

    private val SEARCH_QUERY = "Atletico Madrid"
    private var cachedResponse: MatchStatusResponse? = null

    suspend fun getRecentMatch(): RecentMatch? {
        if (cachedResponse == null) updateMatchStatus()
        return cachedResponse?.recentResult
    }

    suspend fun getUpcomingMatches(): List<UpcomingMatch> {
        if (cachedResponse == null) updateMatchStatus()
        return cachedResponse?.upcomingMatches ?: emptyList()
    }

    @Scheduled(cron = "0 30 1,6 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() = runBlocking {
        println("🕒 [Football API] 정기 업데이트 시작...")
        updateMatchStatus()
    }

    private suspend fun updateMatchStatus() {
        val startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
        val monthlyUsage = collectionLogRepository.sumFootballApiCallCountSince(startOfMonth) ?: 0
        if (monthlyUsage >= 95) return

        var apiCallCount = 0
        val startTime = System.currentTimeMillis()

        try {
            // 사용자 확인 결과: /football-matches-search 엔드포인트 사용
            val response = webClient.get()
                .uri("/football-matches-search?search=$SEARCH_QUERY")
                .retrieve()
                .bodyToMono(JsonNode::class.java)
                .awaitSingle()
            
            apiCallCount++

            // 응답 구조: response.suggestions
            val suggestions = response.path("response").path("suggestions")
            if (!suggestions.isArray) {
                println("⚠️ [Football API] 유효한 검색 결과가 없습니다.")
                return
            }

            val finishedMatches = mutableListOf<JsonNode>()
            val upcomingMatches = mutableListOf<JsonNode>()

            for (match in suggestions) {
                if (match.path("type").asText() != "match") continue

                val homeName = match.path("homeTeamName").asText().lowercase()
                val awayName = match.path("awayTeamName").asText().lowercase()
                
                // 검색 결과 중 진짜 아틀레티코 경기만 필터링
                if (!homeName.contains("atletico madrid") && !awayName.contains("atletico madrid")) continue

                // 수정: finished 필드는 status 객체 내부에 있음
                val isFinished = match.path("status").path("finished").asBoolean()
                if (isFinished) {
                    finishedMatches.add(match)
                } else {
                    upcomingMatches.add(match)
                }
            }

            // 최근 경기 처리
            val recentMatchNode = finishedMatches.maxByOrNull { 
                // status.utcTime 또는 matchDate 사용
                it.path("status").path("utcTime").asText().ifBlank { it.path("matchDate").asText() } 
            }
            val recentMatch = recentMatchNode?.let { node ->
                val matchId = node.path("id").asText()
                val timeStr = node.path("status").path("utcTime").asText().ifBlank { node.path("matchDate").asText() }
                
                val scorers = if (cachedResponse?.recentResult?.matchId == matchId) {
                    cachedResponse!!.recentResult!!.scorers
                } else {
                    try {
                        apiCallCount++
                        fetchScorers(matchId)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                
                val scoreRaw = node.path("score").asText()
                val (homeScore, awayScore) = if (scoreRaw.contains("-")) {
                    val parts = scoreRaw.split("-").map { it.trim().toIntOrNull() ?: 0 }
                    parts.getOrElse(0) { 0 } to parts.getOrElse(1) { 0 }
                } else {
                    0 to 0 
                }
                
                RecentMatch(
                    matchId = matchId,
                    homeTeam = node.path("homeTeamName").asText(),
                    awayTeam = node.path("awayTeamName").asText(),
                    homeScore = homeScore,
                    awayScore = awayScore,
                    scorers = scorers,
                    matchDate = formatUtcTime(timeStr),
                    isHome = node.path("homeTeamName").asText().contains("Atletico Madrid", true),
                    tournament = node.path("leagueName").asText()
                )
            }

            // 다가올 경기 처리 (현재 시간 이후의 경기만 필터링)
            val nowUtc = Instant.now()
            val upcoming = upcomingMatches
                .filter { 
                    try {
                        val timeStr = it.path("status").path("utcTime").asText().ifBlank { it.path("matchDate").asText() }
                        Instant.parse(timeStr).isAfter(nowUtc)
                    } catch (e: Exception) {
                        false
                    }
                }
                .sortedBy { it.path("status").path("utcTime").asText().ifBlank { it.path("matchDate").asText() } }
                .take(3)
                .map { node ->
                    val timeStr = node.path("status").path("utcTime").asText().ifBlank { node.path("matchDate").asText() }
                    val isHome = node.path("homeTeamName").asText().contains("Atletico Madrid", true)
                    UpcomingMatch(
                        opponent = if (isHome) node.path("awayTeamName").asText() else node.path("homeTeamName").asText(),
                        matchDate = formatUtcTime(timeStr),
                        isHome = isHome,
                        tournament = node.path("leagueName").asText()
                    )
                }

            cachedResponse = MatchStatusResponse(recentMatch, upcoming)
            println("✅ [Football API] 업데이트 완료 (API 호출: ${apiCallCount}회)")
            
            saveFootballLog(CollectionStatus.SUCCESS, apiCallCount, startTime)
        } catch (e: Exception) {
            println("❌ [Football API] 업데이트 실패: ${e.message}")
            saveFootballLog(CollectionStatus.FAIL, apiCallCount, startTime, e.message)
        }
    }

    private suspend fun fetchScorers(matchId: String): List<Scorer> {
        // 상세 정보 엔드포인트 시도 (football-match-detail 또는 football-get-match-detail)
        val response = try {
            webClient.get()
                .uri("/football-match-detail?id=$matchId")
                .retrieve()
                .bodyToMono(JsonNode::class.java)
                .awaitSingle()
        } catch (e: Exception) {
            // 실패 시 다른 이름 시도
            webClient.get()
                .uri("/football-get-match-detail?id=$matchId")
                .retrieve()
                .bodyToMono(JsonNode::class.java)
                .awaitSingle()
        }

        val events = response.path("data").path("events")
        val scorers = mutableListOf<Scorer>()
        for (event in events) {
            if (event.path("type").asText() == "goal") {
                scorers.add(
                    Scorer(
                        name = event.path("player").path("name").asText(),
                        minute = "${event.path("time").asInt()}'",
                        isHome = event.path("isHome").asBoolean()
                    )
                )
            }
        }
        return scorers
    }

    private fun saveFootballLog(status: CollectionStatus, callCount: Int, startTime: Long, errorMsg: String? = null) {
        collectionLogRepository.save(
            CollectionLog(
                status = status,
                footballApiCallCount = callCount,
                errorMsg = errorMsg,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        )
    }

    private fun formatUtcTime(utcTime: String): String {
        return try {
            val instant = Instant.parse(utcTime)
            instant.atZone(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (e: Exception) {
            utcTime
        }
    }
}

data class MatchStatusResponse(
    val recentResult: RecentMatch?,
    val upcomingMatches: List<UpcomingMatch>
)

data class RecentMatch(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val scorers: List<Scorer>,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String
)

data class Scorer(
    val name: String,
    val minute: String,
    val isHome: Boolean
)

data class UpcomingMatch(
    val opponent: String,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String
)
