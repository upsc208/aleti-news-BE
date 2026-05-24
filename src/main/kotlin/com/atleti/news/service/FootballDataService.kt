package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.entity.MatchResult
import com.atleti.news.entity.UpcomingMatchEntity
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.MatchResultRepository
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class FootballDataService(
    @Value("\${rapidapi.key}") private val apiKey: String,
    @Value("\${rapidapi.football.host}") private val apiHost: String,
    private val collectionLogRepository: CollectionLogRepository,
    private val matchResultRepository: MatchResultRepository,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    private val matchScraperService: MatchScraperService,
    private val objectMapper: ObjectMapper
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

    suspend fun getRecentMatch(): RecentMatch? {
        val latest = matchResultRepository.findTopByOrderByMatchDateDesc()
        return latest?.let { mapToRecentMatch(it) } ?: run {
            matchScraperService.scrapeAndSaveLatestMatch()?.let { mapToRecentMatch(it) }
        }
    }

    suspend fun getUpcomingMatches(): List<UpcomingMatch> {
        val upcoming = upcomingMatchRepository.findAll()
        if (upcoming.isEmpty()) {
            updateUpcomingMatchesFromApi()
            return upcomingMatchRepository.findAll().map { mapToUpcomingMatch(it) }
        }
        return upcoming.map { mapToUpcomingMatch(it) }
    }

    suspend fun syncUpdate(): String {
        return try {
            val result = matchScraperService.scrapeAndSaveLatestMatch()
            updateUpcomingMatchesFromApi()
            
            if (result != null) "✅ 성공적으로 동기화되었습니다: ${result.homeTeam} vs ${result.awayTeam}"
            else "✅ 향후 일정은 동기화되었으나, 최근 경기 데이터는 찾지 못했습니다."
        } catch (e: Exception) {
            "❌ 에러 발생: ${e.message}"
        }
    }

    @Scheduled(cron = "0 0 7,19 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() {
        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                matchScraperService.scrapeAndSaveLatestMatch()
                updateUpcomingMatchesFromApi()
                saveLog(CollectionStatus.SUCCESS, startTime, 1)
            } catch (e: Exception) {
                saveLog(CollectionStatus.FAIL, startTime, 1, e.message)
            }
        }
    }

    private suspend fun updateUpcomingMatchesFromApi() {
        try {
            val rawResponse = webClient.get()
                .uri("/football-matches-search?search=$SEARCH_QUERY")
                .retrieve()
                .bodyToMono(String::class.java)
                .awaitSingle()

            val json = objectMapper.readTree(rawResponse)
            val suggestions = json.path("response").path("suggestions")
            
            if (suggestions.isArray) {
                val now = Instant.now()
                val sortedUpcoming = suggestions.asSequence()
                    .filter { node ->
                        val hName = node.path("homeTeamName").asText().lowercase()
                        val aName = node.path("awayTeamName").asText().lowercase()
                        val isFinished = node.at("/status/finished").asBoolean()
                        val timeStr = node.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: node.path("matchDate").asText()

                        // 1. 아틀레티코 마드리드 본팀인지 확인
                        val isRealAtleti = (hName.contains("atletico") && hName.contains("madrid")) ||
                                         (aName.contains("atletico") && aName.contains("madrid")) ||
                                         (hName == "atleti" || aName == "atleti")

                        // 2. 여자팀, 유스팀, 타 지역 아틀레티코 제외
                        val isNotOther = !hName.contains("(w)") && !aName.contains("(w)") &&
                                       !hName.contains("femenino") && !aName.contains("femenino") &&
                                       !hName.contains("u19") && !aName.contains("u19") &&
                                       !hName.contains("u21") && !aName.contains("u21") &&
                                       !hName.contains("escobar") && !aName.contains("escobar") &&
                                       !hName.contains("goianiense") && !aName.contains("goianiense") &&
                                       !hName.contains("mineiro") && !aName.contains("mineiro") &&
                                       !hName.contains("paranaense") && !aName.contains("paranaense")

                        val isFuture = try { 
                            Instant.parse(timeStr.replace(" ", "T")).isAfter(now.minusSeconds(3600)) 
                        } catch (e: Exception) { true }

                        isRealAtleti && isNotOther && !isFinished && isFuture
                    }
                    .sortedBy { it.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: it.path("matchDate").asText() }
                    .take(3)
                    .toList()

                if (sortedUpcoming.isNotEmpty()) {
                    upcomingMatchRepository.deleteAll()
                    val entities = sortedUpcoming.map { node ->
                        val hName = node.path("homeTeamName").asText()
                        val aName = node.path("awayTeamName").asText()
                        val isAtletiHome = hName.lowercase().contains("madrid") || hName.lowercase() == "atleti"
                        
                        // 시간 변환 (KST)
                        val utcTimeStr = node.at("/status/utcTime").asText().takeIf { it.isNotBlank() } ?: node.path("matchDate").asText()
                        val kstTimeStr = try {
                            val instant = Instant.parse(utcTimeStr.replace(" ", "T"))
                            instant.atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        } catch (e: Exception) { utcTimeStr }

                        UpcomingMatchEntity(
                            opponent = if (isAtletiHome) aName else hName,
                            matchDate = kstTimeStr,
                            isHome = isAtletiHome,
                            tournament = node.path("leagueName").asText().takeIf { it.isNotBlank() } ?: "Tournament"
                        )
                    }
                    upcomingMatchRepository.saveAll(entities)
                    println("✅ [RapidAPI] 향후 일정 ${entities.size}건 갱신 완료")
                }
            }
        } catch (e: Exception) {
            println("❌ [RapidAPI] 향후 일정 동기화 실패: ${e.message}")
        }
    }

    private fun saveLog(status: CollectionStatus, startTime: Long, callCount: Int, errorMsg: String? = null) {
        collectionLogRepository.save(CollectionLog(
            status = status, 
            footballApiCallCount = callCount,
            errorMsg = errorMsg, 
            executionTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    private fun mapToRecentMatch(entity: MatchResult): RecentMatch {
        val stats: Map<String, StatValue>? = entity.xg?.let { 
            try { objectMapper.readValue<Map<String, StatValue>>(it) } catch (e: Exception) { null } 
        }

        return RecentMatch(
            matchId = entity.matchId,
            homeTeam = entity.homeTeam,
            awayTeam = entity.awayTeam,
            homeScore = entity.homeScore,
            awayScore = entity.awayScore,
            matchDate = entity.matchDate,
            isHome = entity.isHome,
            tournament = entity.tournament,
            scorers = entity.scorers?.let { try { objectMapper.readValue<List<ScorerInfo>>(it) } catch (e: Exception) { null } },
            lineup = entity.lineup?.let { try { objectMapper.readValue<LineupInfo>(it) } catch (e: Exception) { null } },
            substitutions = entity.substitutions?.let { try { objectMapper.readValue<List<SubstitutionInfo>>(it) } catch (e: Exception) { null } },
            cards = entity.cards?.let { try { objectMapper.readValue<List<CardInfo>>(it) } catch (e: Exception) { null } },
            stats = stats ?: emptyMap()
        )
    }

    private fun mapToUpcomingMatch(entity: UpcomingMatchEntity) = UpcomingMatch(
        opponent = entity.opponent,
        matchDate = entity.matchDate,
        isHome = entity.isHome,
        tournament = entity.tournament
    )
}

data class MatchStatusResponse(val recentResult: RecentMatch?, val upcomingMatches: List<UpcomingMatch>)

data class RecentMatch(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String,
    val scorers: List<ScorerInfo>? = null,
    val lineup: LineupInfo? = null,
    val substitutions: List<SubstitutionInfo>? = null,
    val cards: List<CardInfo>? = null,
    val stats: Map<String, StatValue> = emptyMap()
)

data class StatValue(val home: String, val away: String)
data class ScorerInfo(val name: String, val time: String, val assist: String?, val isHome: Boolean)
data class SubstitutionInfo(val time: String, val outName: String, val inName: String, val isHome: Boolean)
data class CardInfo(val name: String, val time: String, val shirt: String?, val color: String, val isHome: Boolean)
data class PlayerInfo(val name: String, val shirt: String?)
data class TeamLineupInfo(val formation: String?, val starters: List<PlayerInfo>)
data class LineupInfo(val home: TeamLineupInfo, val away: TeamLineupInfo)

data class UpcomingMatch(val opponent: String, val matchDate: String, val isHome: Boolean, val tournament: String)
