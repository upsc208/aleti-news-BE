package com.atleti.news.service

import com.atleti.news.entity.CollectionLog
import com.atleti.news.entity.CollectionStatus
import com.atleti.news.entity.MatchResult
import com.atleti.news.entity.UpcomingMatch
import com.atleti.news.repository.CollectionLogRepository
import com.atleti.news.repository.MatchResultRepository
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Service
class FootballDataService(
    private val collectionLogRepository: CollectionLogRepository,
    private val matchResultRepository: MatchResultRepository,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    private val matchScraperService: MatchScraperService,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val UPCOMING_LIMIT = 3

    /** 킥오프 후 이 시간까지는 "다가올 경기"로 계속 노출한다 (채팅방 오픈 구간과 동일, redis-design 6.6) */
    private val KEEP_AFTER_KICKOFF = Duration.ofHours(3)

    /** DB가 비었을 때 요청마다 FotMob을 두드리지 않도록 하는 최소 간격 */
    private val REFRESH_COOLDOWN = Duration.ofMinutes(10)
    private val lastRefreshAttempt = AtomicReference(Instant.EPOCH)

    suspend fun getRecentMatch(): RecentMatch? {
        val latest = matchResultRepository.findTopByOrderByMatchDateDesc()
        return latest?.let { mapToRecentMatch(it) } ?: run {
            matchScraperService.scrapeAndSaveLatestMatch()?.let { mapToRecentMatch(it) }
        }
    }

    suspend fun getUpcomingMatches(): List<UpcomingMatchResponse> {
        loadFreshUpcoming().takeIf { it.isNotEmpty() }?.let { return it.map { e -> mapToUpcomingMatch(e) } }

        // 비어 있거나 전부 지난 경기뿐이면 즉시 갱신을 시도한다 (쿨다운 내에는 재시도하지 않음)
        if (claimRefreshSlot()) {
            runCatching { updateUpcomingMatches() }
                .onFailure { println("❌ [FotMob] 향후 일정 동기화 실패: ${it.message}") }
        }
        return loadFreshUpcoming().map { mapToUpcomingMatch(it) }
    }

    suspend fun syncUpdate(): String {
        return try {
            val result = matchScraperService.scrapeAndSaveLatestMatch()
            val count = updateUpcomingMatches()

            if (result != null) "✅ 동기화 완료 — 최근 경기: ${result.homeTeam} vs ${result.awayTeam} / 향후 일정 ${count}건"
            else "✅ 향후 일정 ${count}건은 동기화되었으나, 최근 경기 데이터는 찾지 못했습니다."
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
                updateUpcomingMatches()
                saveLog(CollectionStatus.SUCCESS, startTime, 0)
            } catch (e: Exception) {
                // 예전에는 예외가 갱신 함수 안에서 삼켜져 실패해도 SUCCESS로 기록됐다
                println("❌ [FotMob] 예정 경기 갱신 실패: ${e.message}")
                saveLog(CollectionStatus.FAIL, startTime, 0, e.message)
            }
        }
    }

    /** 아직 안 끝난 경기만, 킥오프 순으로 */
    private fun loadFreshUpcoming(): List<UpcomingMatch> {
        val cutoff = Instant.now().minus(KEEP_AFTER_KICKOFF)
        return upcomingMatchRepository.findAllByOrderByMatchDateAsc()
            .filter { parseKickoff(it.matchDate)?.isAfter(cutoff) == true }
    }

    /**
     * FotMob에서 다가올 경기를 받아 upcoming_match 테이블을 통째로 교체한다.
     *
     * 스크랩이 실패하면 예외가 그대로 올라가고 기존 데이터는 손대지 않는다.
     * 반대로 성공했다면 그 결과가 최신 상태이므로, 0건이어도(시즌 종료 등) 지난 경기는 지운다.
     * — 예전 구현은 결과가 비면 아무것도 하지 않아 지난 경기가 영원히 남았다.
     */
    private suspend fun updateUpcomingMatches(): Int {
        lastRefreshAttempt.set(Instant.now())
        val fresh = matchScraperService.scrapeUpcomingMatches(UPCOMING_LIMIT)

        withContext(Dispatchers.IO) {
            transactionTemplate.executeWithoutResult {
                upcomingMatchRepository.deleteAllInBatch()
                upcomingMatchRepository.flush()
                upcomingMatchRepository.saveAll(fresh)
            }
        }
        println("✅ [FotMob] 향후 일정 ${fresh.size}건 갱신 완료")
        return fresh.size
    }

    /** 쿨다운이 지났으면 갱신 권한을 가져간다. 동시 요청 중 하나만 true를 받는다. */
    private fun claimRefreshSlot(): Boolean {
        val now = Instant.now()
        val last = lastRefreshAttempt.get()
        return last.isBefore(now.minus(REFRESH_COOLDOWN)) && lastRefreshAttempt.compareAndSet(last, now)
    }

    private fun parseKickoff(raw: String): Instant? =
        runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }
            .recoverCatching { Instant.parse(raw.replace(" ", "T")) }
            .getOrNull()

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
            tournament = TournamentNameKo.toKorean(entity.tournament),
            scorers = entity.scorers?.let { try { objectMapper.readValue<List<ScorerInfo>>(it) } catch (e: Exception) { null } },
            lineup = entity.lineup?.let { try { objectMapper.readValue<LineupInfo>(it) } catch (e: Exception) { null } },
            substitutions = entity.substitutions?.let { try { objectMapper.readValue<List<SubstitutionInfo>>(it) } catch (e: Exception) { null } },
            cards = entity.cards?.let { try { objectMapper.readValue<List<CardInfo>>(it) } catch (e: Exception) { null } },
            stats = stats ?: emptyMap()
        )
    }

    private fun mapToUpcomingMatch(entity: UpcomingMatch) = UpcomingMatchResponse(
        // 경기 전 채팅방 키. 이게 없으면 프론트가 킥오프 전에 방으로 들어갈 방법이 없다
        matchId = entity.matchId,
        opponent = entity.opponent,
        matchDate = entity.matchDate,
        isHome = entity.isHome,
        tournament = TournamentNameKo.toKorean(entity.tournament)
    )
}

data class MatchStatusResponse(val recentResult: RecentMatch?, val upcomingMatches: List<UpcomingMatchResponse>)

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

data class UpcomingMatchResponse(
    /** 채팅방 키 (ChatService.findKickoffTime이 이 값으로 킥오프를 찾는다). 스크랩에서 못 얻으면 null */
    val matchId: String?,
    val opponent: String,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String
)
