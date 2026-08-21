package com.atleti.news.service

import com.atleti.news.dto.ChatMessageType
import com.atleti.news.dto.LiveMatchState
import com.atleti.news.dto.LivePeriods
import com.atleti.news.dto.LiveSubstitution
import com.atleti.news.dto.LiveScorer
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 라이브 경기 폴러 (docs/live-match-feed-design.md 5장).
 *
 * 한 턴에 하는 일:
 * ```
 * ① Redis 리더 락 획득 (실패하면 이번 턴 스킵)
 * ② 네이버 API 호출 (2.6KB)
 * ③ live:state 에 스냅샷 저장            ← 입장자 조회용
 * ④ live:goals 와 비교
 *      · 새로 생긴 골 → GOAL 발행
 *      · 사라진 골    → GOAL_CANCELLED 발행
 * ```
 * 브로드캐스트 경로는 새로 만들지 않는다. [ChatService]가 기존 `chat.messages` 채널에
 * 발행하면 [ChatMessageRelay]가 그대로 `/topic/chat/{matchId}`로 밀어 준다.
 *
 * **라이브 정보는 부가 기능이다.** 어디서 실패하든 WARN 로그만 남기고 넘어간다 —
 * 채팅 자체는 정상 동작해야 한다 (설계 8.5).
 */
@Service
class LiveMatchPoller(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val naverSportsClient: NaverSportsClient,
    private val fotMobLiveClient: FotMobLiveClient,
    private val liveMatchMapper: LiveMatchMapper,
    private val chatService: ChatService,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    @Value("\${atleti.live.enabled}") private val enabled: Boolean,
    @Value("\${atleti.live.delay-seconds:30}") private val delaySeconds: Long
) {
    private val log = LoggerFactory.getLogger(LiveMatchPoller::class.java)

    /** 스케줄러 스레드 풀은 1개뿐이라(다른 배치와 공유) 여기서 붙잡고 있으면 안 된다 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turnInProgress = AtomicBoolean(false)

    /** 이 인스턴스의 리더 락 소유자 표식 */
    private val instanceId = UUID.randomUUID().toString()

    /** 네이버가 흔들릴 때 쉬어 갈 시각. 리더 한 대만 폴링하므로 메모리에 둬도 된다 */
    private val backoffUntil = ConcurrentHashMap<String, Instant>()
    private val failureCount = ConcurrentHashMap<String, Int>()

    /** FotMob 폴백을 유지할 기한. 이 시각까지는 네이버를 다시 건드리지 않는다 */
    private val fotMobUntil = ConcurrentHashMap<String, Instant>()

    /** 경기별 `골 키 → 라틴 이름`. 한 경기 분량이라 메모리에 둔다 */
    private val latinNames = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** 경기별 시계 기준점 (전·후반 실제 시작 시각) */
    private val clockAnchors = ConcurrentHashMap<String, LivePeriods>()

    /** 직전 턴의 경기 상태. 바뀐 순간을 FotMob 재조회 신호로 쓴다 */
    private val lastStatus = ConcurrentHashMap<String, String>()

    /**
     * FotMob에서 받아 둔 교체 목록. 네이버에는 이 데이터가 없어서 주 스냅샷에 얹어 준다.
     *
     * 골처럼 매 턴 새로 받는 게 아니라 **FotMob을 부를 때만** 갱신되므로, 마지막으로 받은
     * 것을 들고 있다가 계속 실어 보낸다. 전체 목록이라 덮어쓰기로 충분하다.
     */
    private val substitutions = ConcurrentHashMap<String, List<LiveSubstitution>>()

    /**
     * FotMob에서 한 번만 받아 두면 되는 표기들 — 경기 내내 안 바뀐다.
     * 보드가 로마자로 그려야 해서 필요한데 네이버는 한글만 준다.
     */
    private data class LatinLabels(val homeTeam: String, val awayTeam: String, val competition: String)

    private val latinLabels = ConcurrentHashMap<String, LatinLabels>()

    /** FotMob 보강(이름·시계)을 다시 시도할 시각 */
    private val fotMobRetryAfter = ConcurrentHashMap<String, Instant>()

    /**
     * 아직 내보내지 않은 스냅샷 — `{가져온 시각, 스냅샷}`.
     *
     * 라이브 정보를 **중계보다 늦게** 내보내기 위한 대기열이다 ([takeDue] 참고).
     * 인스턴스 안에만 둔다. 재시작하면 비므로 [delaySeconds]만큼 발행이 쉬는데,
     * 스냅샷은 매번 전체 상태라 다시 차오르면 그대로 이어진다.
     */
    private val feedBuffer = ConcurrentHashMap<String, ArrayDeque<Pair<Instant, LiveSnapshot>>>()

    /** 예정 경기 목록 캐시 — 경기가 없는 대부분의 시간에 5초마다 DB를 두드리지 않기 위한 것 */
    @Volatile private var activeCache: List<ActiveMatch> = emptyList()
    @Volatile private var activeCachedAt: Instant = Instant.EPOCH

    private data class ActiveMatch(val matchId: String, val kickoff: OffsetDateTime)

    /**
     * 이 폴러만 쓰는 타이머 스레드.
     *
     * `@Scheduled`를 쓰지 않는 이유: 스프링의 스케줄러 풀은 기본이 **스레드 1개**인데
     * 뉴스 파이프라인([NewsPipelineService])이 `runBlocking`으로 그 하나를 몇 분씩 붙잡는다.
     * 같이 쓰면 15분마다 라이브가 그 시간만큼 멈춘다 — 경기 중에는 그대로 골 알림 지연이다.
     * 풀 크기를 늘리는 방법도 있지만, 그러면 뉴스 배치끼리 동시에 돌게 되어
     * 1GB VM에서 스크래핑·Gemini 부하가 겹친다. 여기만 따로 떼는 편이 안전하다.
     */
    private val ticker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "live-match-poller").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        if (!enabled) {
            log.info("ℹ️ [Live] 라이브 피드가 꺼져 있습니다 (atleti.live.enabled=false)")
            return
        }
        ticker.scheduleWithFixedDelay(::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stop() {
        ticker.shutdownNow()
        scope.cancel()
    }

    /**
     * 한 턴. **예외를 절대 밖으로 내보내지 않는다** —
     * `scheduleWithFixedDelay`는 작업이 던지면 그 뒤로 영영 다시 부르지 않는다.
     */
    fun tick() {
        try {
            // 앞 턴이 아직 안 끝났으면 건너뛴다 (네이버가 느릴 때 턴이 겹치는 것을 막는다)
            if (!turnInProgress.compareAndSet(false, true)) return

            scope.launch {
                try {
                    pollAll()
                } catch (e: Exception) {
                    log.warn("⚠️ [Live] 폴링 턴 실패: {}", e.message)
                } finally {
                    turnInProgress.set(false)
                }
            }
        } catch (e: Exception) {
            turnInProgress.set(false)
            log.warn("⚠️ [Live] 폴링 턴을 띄우지 못했습니다: {}", e.message)
        }
    }

    private suspend fun pollAll() {
        for (match in activeMatches()) {
            // 한 경기가 실패해도 나머지는 계속 돈다
            try {
                pollMatch(match)
            } catch (e: Exception) {
                log.warn("⚠️ [Live] 폴링 실패 (matchId={}): {}", match.matchId, e.message)
            }
        }
    }

    private suspend fun pollMatch(match: ActiveMatch) {
        val matchId = match.matchId
        if (isSettled(matchId, match.kickoff)) return

        // 리더가 아니면 이번 턴은 남의 몫이다. 락은 자기 것이면 그대로 이어 잡는다
        if (!acquireLeadership(matchId)) return

        val previous = readState(matchId)
        val fetched = fetchSnapshot(match, previous) ?: return

        // 알림을 내보내기 **전에** 라틴 이름을 채운다 — 골 메시지에도 같이 실려야
        // LED가 첫 연출부터 로마자로 그린다.
        //
        // **이 단계까지는 실시간으로 한다.** 라틴 이름·시계 기준점은 지금 받아 둬야 하고,
        // 특히 하프타임 기준점은 그 순간의 시각으로 찍혀야 정확하다. 늦추는 것은 그 다음이다
        val enriched = withLatinNames(matchId, fetched, started = !OffsetDateTime.now().isBefore(match.kickoff))

        // 중계보다 늦게 내보낸다 — 아직 충분히 묵지 않았으면 이번 턴은 조용히 넘어간다
        val snapshot = takeDue(matchId, enriched) ?: return

        val state = toState(snapshot, previous, match.kickoff, latinLabels[matchId])
        writeState(matchId, state)

        if (previous == null) {
            // 첫 턴은 기준선만 잡는다. 이게 없으면 폴링을 늦게 시작한 경기에서
            // 40분 전 골까지 지금 막 들어간 것처럼 한꺼번에 알린다
            seedGoals(matchId, snapshot)
        } else {
            val announced = publishGoalChanges(matchId, snapshot, state)
            // 골 알림이 나갔으면 거기에 이미 같은 [state]가 실려 있다. 안 나갔을 때만
            // 따로 알린다 — 하프타임처럼 **골 없이 시계만 바뀌는** 순간이 여기서 걸린다
            if (!announced && clockChanged(previous, state)) {
                chatService.publishLiveState(matchId, state)
                // statusCode를 같이 남긴다 — 8/20 경기에서 하프타임의 실제 코드를 몰라
                // 종료 판정을 방어적으로 짤 수밖에 없었다 (NaverSportsClient.isFinished)
                log.info(
                    "🕒 [Live] 상태 변화 발행 — {} → {} [code={}] / periods={} (matchId={})",
                    previous.status, state.status, snapshot.statusCode, state.periods, matchId
                )
            }
        }

        markSettledIfFinished(matchId, snapshot)
    }

    // ── 라틴 이름 채우기 ──────────────────────────────────────────────────

    /**
     * 네이버가 준 한글 득점자에 FotMob의 라틴 표기를 붙인다.
     *
     * 음역표를 만들지 않는다. **같은 경기의 같은 분에 들어간 골은 같은 골**이므로
     * 팀(`isHome`)과 분(±[MINUTE_TOLERANCE])만으로 짝이 지어진다. 새로 들어온 선수든
     * 유스에서 막 올라온 선수든 표에 등록할 필요 없이 그대로 동작한다.
     *
     * 한 번 찾은 이름은 경기가 끝날 때까지 들고 있는다. 아직 못 찾은 골이 있을 때만
     * [LATIN_RETRY] 간격으로 다시 물어본다 — FotMob이 네이버보다 늦게 반영하는 경우가
     * 있어서 한 번 실패했다고 포기하면 안 된다.
     */
    private suspend fun withLatinNames(
        matchId: String,
        snapshot: LiveSnapshot,
        started: Boolean
    ): LiveSnapshot {
        // FotMob으로 폴백한 상태면 이름도 팀명도 시계 기준점도 이미 그 응답에 들어 있다
        if (snapshot.source == LiveSource.FOTMOB) {
            latinLabels[matchId] = LatinLabels(snapshot.homeTeam, snapshot.awayTeam, snapshot.competition)
            return snapshot
        }

        val known = latinNames.getOrPut(matchId) { ConcurrentHashMap() }
        val periods = clockAnchors[matchId]

        val needsNames = snapshot.goals.any { !known.containsKey(it.key) }
        // 팀명은 경기 내내 안 바뀐다. 한 번만 받으면 되고, 킥오프 전에도 보드에 필요하다
        val needsTeams = !latinLabels.containsKey(matchId)
        // 후반 시작 시각까지 받으면 시계가 완성된다. 시작 전에는 물어볼 이유가 없다
        val needsClock = started && !snapshot.finished && periods?.secondHalfStarted == null

        // 교체는 **네이버에 없어서** FotMob을 계속 봐야 안다. 경기 내내 물어보는 유일한 이유다
        val needsSubs = started && !snapshot.finished

        // 교체만 기다리는 구간이 경기의 대부분이다. 시계 기준점이 다 모인 뒤에는
        // 이 간격이 그대로 FotMob 호출 주기가 된다 (응답 243KB — 5초마다 때릴 값이 아니다)
        val wait = when {
            needsNames -> LATIN_RETRY
            needsClock -> clockRetryInterval(periods, Instant.now())
            else -> SUBS_RETRY
        }
        val nextTry = fotMobRetryAfter[matchId]

        // 상태가 방금 바뀌었으면 간격을 무시하고 지금 물어본다.
        //
        // **네이버가 FotMob보다 먼저 안다.** 5초마다 보고 있으니 하프타임·후반 시작을
        // 그 안에 알려주는데, 시계 기준점은 FotMob에만 있다. 간격을 기다리면 정작
        // 기준점이 필요한 순간을 지나쳐 버린다 — 리허설에서 하프타임이 통째로
        // 지나간 뒤에야 firstHalfEnded가 도착했다.
        val statusShifted = lastStatus.put(matchId, snapshot.status) != snapshot.status
        val due = statusShifted || nextTry == null || nextTry.isBefore(Instant.now())

        if ((needsNames || needsTeams || needsClock || needsSubs) && due) {
            fotMobRetryAfter[matchId] = Instant.now().plus(wait)
            val fotMob = runCatching { fotMobLiveClient.fetchSnapshot(matchId) }
                .onFailure { log.warn("⚠️ [Live] FotMob 보강 실패 (matchId={}): {}", matchId, it.message) }
                .getOrNull()

            if (fotMob != null) {
                known.putAll(pairLatinNames(snapshot.goals, fotMob.goals))
                if (fotMob.homeTeam.isNotBlank() && fotMob.awayTeam.isNotBlank()) {
                    latinLabels[matchId] = LatinLabels(fotMob.homeTeam, fotMob.awayTeam, fotMob.competition)
                }
                if (fotMob.substitutions.isNotEmpty()) substitutions[matchId] = fotMob.substitutions
                fotMob.periods?.let { fresh ->
                    if (fresh.secondHalfStarted != null && periods?.secondHalfStarted == null) {
                        log.info("ℹ️ [Live] 후반 시작 {} — 경기 시계 기준점 확보 (matchId={})",
                            fresh.secondHalfStarted, matchId)
                    }
                    clockAnchors[matchId] = mergePeriods(clockAnchors[matchId], fresh)
                }
            }
        }

        return snapshot.copy(
            goals = snapshot.goals.map { it.copy(nameLatin = known[it.key]) },
            substitutions = substitutions[matchId] ?: snapshot.substitutions,
            periods = fillClockFromStatus(matchId, snapshot)
        )
    }

    /**
     * FotMob이 안 준 시계 기준점을 **네이버 상태로 메꾼다.**
     *
     * 2026-08-20 말라가전에서 FotMob의 `halfs.firstHalfEnded`가 전반 종료 15분이 지나도록
     * 끝내 안 왔다. 그동안 화면 시계는 멈추지 않고 계속 돌았고, 후반이 시작돼도 45:00으로
     * 되돌아가지 않았다. FotMob 하나에만 매달린 것이 원인이다.
     *
     * 네이버는 같은 순간을 `전반종료`로 5초 안에 알려준다. 정확한 시각은 아니지만
     * **폴링 주기(5초) 안쪽의 오차**이고, 시계가 아예 안 멈추는 것보다 훨씬 낫다.
     *
     * FotMob 값이 나중에라도 오면 그쪽이 이긴다 — 여기서는 비어 있을 때만 채운다.
     */
    private fun fillClockFromStatus(matchId: String, snapshot: LiveSnapshot): LivePeriods? {
        val anchor = clockAnchors[matchId] ?: return null
        // 전반 시작조차 모르면 메꿀 기준이 없다. 엉뚱한 시각을 지어내지 않는다
        if (anchor.firstHalfStarted == null) return anchor

        val now = OffsetDateTime.now()

        if (snapshot.halftime && anchor.firstHalfEnded == null) {
            val filled = anchor.copy(firstHalfEnded = now)
            clockAnchors[matchId] = filled
            log.info("ℹ️ [Live] 전반 종료를 네이버 상태로 잡았습니다 ({}) — FotMob 미제공 (matchId={})",
                snapshot.status, matchId)
            return filled
        }

        // 하프타임이 끝나고 다시 경기중으로 돌아온 순간 = 후반 시작.
        // 이게 있어야 화면이 45:00부터 다시 센다
        if (!snapshot.halftime && !snapshot.finished &&
            anchor.firstHalfEnded != null && anchor.secondHalfStarted == null
        ) {
            val filled = anchor.copy(secondHalfStarted = now)
            clockAnchors[matchId] = filled
            log.info("ℹ️ [Live] 후반 시작을 네이버 상태로 잡았습니다 — FotMob 미제공 (matchId={})", matchId)
            return filled
        }

        return anchor
    }


    // ── 소스 선택 (설계 8.3) ──────────────────────────────────────────────

    /**
     * 네이버를 우선 쓰고, 안 되면 FotMob으로 내려간다.
     *
     * 첫 실패에 바로 갈아타지 않는다 — 한 번씩 나는 타임아웃까지 소스 교체로 처리하면
     * 두 소스를 계속 오가게 되고, 오갈 때마다 골 정렬(재기준선)이 돌아 알림이 늦어진다.
     * 반대로 **매핑이 없을 때는 즉시** 내려간다: FotMob은 matchId를 그대로 쓰므로
     * 네이버 gameId를 못 찾은 것이 FotMob에게는 아무 장애도 아니다.
     */
    private suspend fun fetchSnapshot(match: ActiveMatch, previous: LiveMatchState?): LiveSnapshot? {
        val matchId = match.matchId
        val onFotMob = fotMobUntil[matchId]?.isAfter(Instant.now()) == true
        val source = if (onFotMob) LiveSource.FOTMOB else LiveSource.NAVER

        if (!shouldPollNow(match, previous, source)) return null
        if (onFotMob) return fetchFotMob(matchId)

        if (backoffUntil[matchId]?.isAfter(Instant.now()) == true) return null

        val gameId = liveMatchMapper.resolveGameId(matchId, match.kickoff)
            ?: return switchToFotMob(matchId, "네이버 gameId 매핑 없음")

        return try {
            naverSportsClient.fetchGame(gameId).toSnapshot().also {
                failureCount.remove(matchId)
                if (fotMobUntil.remove(matchId) != null) {
                    log.info("✅ [Live] 네이버가 회복되어 되돌아갑니다 (matchId={})", matchId)
                }
            }
        } catch (e: NaverRateLimitException) {
            // 캐시 없이 원본을 직접 때리는 처지라 429는 즉시 크게 물러선다 (설계 5.4).
            // 우리를 막고 있는 상대를 계속 두드리는 것보다 예비 소스로 가는 편이 낫다
            backoffUntil[matchId] = Instant.now().plus(RATE_LIMIT_BACKOFF)
            switchToFotMob(matchId, "네이버 429")
        } catch (e: NaverSportsException) {
            applyBackoff(matchId, e)
            if ((failureCount[matchId] ?: 0) >= FAILURES_BEFORE_FALLBACK) {
                switchToFotMob(matchId, "네이버 연속 실패 ${failureCount[matchId]}회")
            } else {
                null
            }
        }
    }

    /** 폴백으로 전환하고 이번 턴 값까지 받아 온다. FotMob도 실패하면 이번 턴은 빈손이다 */
    private suspend fun switchToFotMob(matchId: String, reason: String): LiveSnapshot? {
        fotMobUntil[matchId] = Instant.now().plus(FOTMOB_STICKY)
        log.warn(
            "⚠️ [Live] FotMob으로 폴백합니다 — {} (matchId={}). 선수 이름이 영문으로 바뀝니다",
            reason, matchId
        )
        return fetchFotMob(matchId)
    }

    private suspend fun fetchFotMob(matchId: String): LiveSnapshot? =
        try {
            fotMobLiveClient.fetchSnapshot(matchId)
        } catch (e: Exception) {
            // 두 소스가 모두 죽은 상태. 채팅은 계속 돌아야 하므로 로그만 남긴다 (설계 8.5)
            log.warn("⚠️ [Live] FotMob 폴백도 실패 (matchId={}): {}", matchId, e.message)
            null
        }

    // ── ① 리더 락 (설계 5.3) ──────────────────────────────────────────────

    /**
     * 폴러가 둘 이상 도는 것을 막는다. `AtomicReference`류의 JVM 안 잠금과 달리
     * 배포 중 인스턴스가 겹치거나 서버를 늘려도 네이버 요청이 두 배가 되지 않는다.
     *
     * 락 TTL(10초)이 폴링 주기(5초)보다 길어서 **자기가 쥔 락은 이어 잡아야 한다** —
     * 아니면 리더가 자기 락에 막혀 다음 턴을 통째로 건너뛴다.
     */
    private fun acquireLeadership(matchId: String): Boolean {
        val key = LiveMatchKeys.lock(matchId)
        val ops = redis.opsForValue()
        if (ops.setIfAbsent(key, instanceId, LOCK_TTL) == true) return true

        if (ops.get(key) != instanceId) return false
        ops.set(key, instanceId, LOCK_TTL)   // 리더 유지 — 만료 시계를 다시 감는다
        return true
    }

    // ── ② 폴링 구간·주기 ──────────────────────────────────────────────────

    /**
     * 킥오프 전에는 [PRE_KICKOFF_INTERVAL]로 느리게, 킥오프부터는 [TICK_INTERVAL_MS]마다 촘촘하게.
     *
     * 설계 5.4의 폴링 구간은 "킥오프 ~ 종료 +10분"이지만, 그러면 킥오프 전 입장자에게
     * 내려줄 스냅샷이 없어 6.2의 "경기 전이면 경기전 0-0으로 내려준다"를 지킬 수 없다.
     * 방 오픈 시점(킥오프 −1h)부터 1분에 한 번만 찍어 두면 시간당 60회로 그 요구가 채워진다.
     *
     * FotMob은 응답이 70배 커서(180KB) 5초로 때리면 대역폭만 먹는다. CDN 캐시까지 붙어 있어
     * 촘촘히 불러도 같은 값이 돌아올 뿐이므로 [FOTMOB_INTERVAL]로 늦춘다.
     */
    private fun shouldPollNow(match: ActiveMatch, previous: LiveMatchState?, source: LiveSource): Boolean {
        if (previous == null) return true

        val minimum = when {
            OffsetDateTime.now().isBefore(match.kickoff) -> PRE_KICKOFF_INTERVAL
            source == LiveSource.FOTMOB -> FOTMOB_INTERVAL
            else -> Duration.ZERO
        }
        return Duration.between(previous.updatedAt, OffsetDateTime.now()) >= minimum
    }

    /**
     * 경기 종료 +10분이 지났는지. 폴링을 멈춘 뒤에도 스냅샷은 TTL(6h) 동안 남아 조회된다.
     *
     * **킥오프보다 앞선 표식은 버린다.** 아직 시작도 안 한 경기가 이미 끝나 있을 수는 없다 —
     * 같은 matchId로 앞선 경기를 돌린 흔적(리허설)이거나 잘못 남은 값이다. 그대로 두면
     * 폴러가 그 경기를 통째로 건너뛰는데, 로그에도 아무것도 안 남아 원인을 찾기 어렵다.
     */
    private fun isSettled(matchId: String, kickoff: OffsetDateTime): Boolean {
        val endedAt = runCatching { redis.opsForValue().get(LiveMatchKeys.end(matchId)) }.getOrNull()
            ?: return false
        val ended = endedAt.toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return false

        if (ended.isBefore(kickoff.toInstant())) {
            log.warn("⚠️ [Live] 킥오프({})보다 앞선 종료 표식({})을 버립니다 (matchId={})", kickoff, ended, matchId)
            runCatching { redis.delete(LiveMatchKeys.end(matchId)) }
            return false
        }
        return Instant.now().isAfter(ended.plus(SETTLE_GRACE))
    }

    private fun markSettledIfFinished(matchId: String, snapshot: LiveSnapshot) {
        if (!snapshot.finished) return
        redis.opsForValue().setIfAbsent(
            LiveMatchKeys.end(matchId), Instant.now().toEpochMilli().toString(), LiveMatchKeys.LIVE_TTL
        )
        feedBuffer.remove(matchId)
    }

    /**
     * 방금 가져온 스냅샷을 대기열에 넣고, **[delaySeconds]만큼 묵은 것**을 꺼내 준다.
     * 아직 그만큼 묵은 게 없으면 null — 이번 턴은 아무것도 내보내지 않는다.
     *
     * ## 왜 늦추나
     *
     * 봇이 중계보다 빠르면 실시간 정보가 아니라 **스포일러**다. 2026-08-20 라리가
     * 말라가전에서 쿠팡플레이가 실제보다 27~33초 늦었는데, 골 알림은 실제 골로부터
     * 2~20초면 나갔다. 시청자가 골 장면을 보기 **7~31초 전에** 채팅이 먼저 알려준 셈이다.
     *
     * 시계만 밀면 스코어보드 숫자가 먼저 올라가 똑같이 새어 나간다. 그래서 스코어·득점자·
     * 골 알림·하프타임까지 **전부 같은 폭으로** 늦춘다 — 화면이 한 몸으로 움직인다.
     *
     * ## 왜 이 방식인가
     *
     * 발행만 따로 보류하지 않고 **입력을 늦춘다.** 스냅샷은 매번 전체 상태라, 늦게 들어온
     * 스냅샷에 기존 diff 로직을 그대로 태우면 골·취소·상태 변화가 저절로 같이 밀린다.
     * 보류 큐를 따로 두면 취소가 골을 앞지르는 순서 문제를 직접 풀어야 한다.
     *
     * 덤으로 **넣자마자 VAR로 취소된 골은 아예 안 나간다.** 두 변화가 같은 대기 구간
     * 안에서 상쇄되기 때문인데, 시청자가 보지도 못한 골을 알렸다 취소하는 것보다 낫다.
     */
    private fun takeDue(matchId: String, fresh: LiveSnapshot): LiveSnapshot? {
        if (delaySeconds == 0L) return fresh

        val queue = feedBuffer.getOrPut(matchId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(Instant.now() to fresh)

            return drainDue(queue, Instant.now().minusSeconds(delaySeconds))
        }
    }

    // ── ③ 스냅샷 ─────────────────────────────────────────────────────────

    private fun toState(
        snapshot: LiveSnapshot,
        previous: LiveMatchState?,
        kickoff: OffsetDateTime?,
        latin: LatinLabels?
    ) = LiveMatchState(
        status = snapshot.status,
        // FotMob은 팀 이름이 영문이다. 직전에 네이버가 준 한글 이름이 있으면 그대로 쓴다 —
        // 경기 도중 스코어보드의 팀 이름만 영문으로 갈아엎히면 고장으로 보인다.
        // (득점자 이름까지는 못 살린다. 그건 폴백을 쓰는 값이다)
        homeTeam = preferKorean(snapshot.source, previous?.homeTeam, snapshot.homeTeam),
        awayTeam = preferKorean(snapshot.source, previous?.awayTeam, snapshot.awayTeam),
        homeTeamLatin = latin?.homeTeam,
        awayTeamLatin = latin?.awayTeam,
        competition = snapshot.competition.takeIf { it.isNotBlank() },
        competitionLatin = latin?.competition?.takeIf { it.isNotBlank() },
        // VAR로 되돌아갈 수 있으므로 누적이 아니라 현재 상태 덮어쓰기다 (설계 5.4)
        homeScore = snapshot.homeScore,
        awayScore = snapshot.awayScore,
        scorers = snapshot.goals.map {
            LiveScorer(it.name, it.time, it.addedTime, it.isHome, it.ownGoal, it.nameLatin)
        },
        substitutions = snapshot.substitutions,
        kickoff = kickoff,
        periods = delayed(snapshot.periods, delaySeconds),
        updatedAt = OffsetDateTime.now()
    )



    private fun preferKorean(source: LiveSource, previous: String?, current: String): String =
        if (source == LiveSource.FOTMOB && !previous.isNullOrBlank()) previous else current

    private fun readState(matchId: String): LiveMatchState? =
        runCatching {
            redis.opsForValue().get(LiveMatchKeys.state(matchId))
                ?.let { objectMapper.readValue(it, LiveMatchState::class.java) }
        }.getOrNull()

    private fun writeState(matchId: String, state: LiveMatchState) {
        redis.opsForValue().set(
            LiveMatchKeys.state(matchId), objectMapper.writeValueAsString(state), LiveMatchKeys.LIVE_TTL
        )
    }

    // ── ④ 골 비교 (설계 5.3) ──────────────────────────────────────────────

    /** 폴링을 시작하기 전에 이미 들어가 있던 골 — 알리지 않고 "본 것"으로만 표시한다 */
    private fun seedGoals(matchId: String, snapshot: LiveSnapshot) {
        writeSource(matchId, snapshot.source)
        if (snapshot.goals.isEmpty()) return

        val key = LiveMatchKeys.goals(matchId)
        redis.opsForSet().add(key, *snapshot.goals.map { it.key }.toTypedArray())
        redis.expire(key, LiveMatchKeys.LIVE_TTL)
        log.info("ℹ️ [Live] 기존 골 {}건을 기준선으로 잡았습니다 (matchId={})", snapshot.goals.size, matchId)
    }

    /** 이번 턴에 [state]를 실은 메시지를 하나라도 내보냈으면 true */
    private fun publishGoalChanges(matchId: String, snapshot: LiveSnapshot, state: LiveMatchState): Boolean {
        val storedSource = readSource(matchId)
        if (storedSource != null && storedSource != snapshot.source) {
            return realignAcrossSources(matchId, snapshot, state, storedSource)
        }
        var announced = false

        val key = LiveMatchKeys.goals(matchId)
        val setOps = redis.opsForSet()
        val known = setOps.members(key) ?: emptySet()
        val current = snapshot.goals.associateBy { it.key }

        // 새 골 — SADD가 1을 돌려줄 때만 발행한다. 확인과 기록이 한 번의 원자 연산이라
        // 인스턴스가 겹쳐도 같은 골이 두 번 나가지 않는다
        for ((goalKey, goal) in current) {
            if (setOps.add(key, goalKey) != 1L) continue
            chatService.publishLiveMessage(matchId, ChatMessageType.GOAL, goalText(goal), state)
            announced = true
            log.info("⚽ [Live] {} {} (matchId={})", goal.minute, goal.name, matchId)
        }
        redis.expire(key, LiveMatchKeys.LIVE_TTL)
        // 스냅샷은 매 턴 다시 쓰여 TTL이 갱신되지만 이 둘은 아니다. 함께 늘려주지 않으면
        // 긴 경기에서 소스 표식만 먼저 사라져 다음 전환을 감지하지 못한다
        redis.expire(LiveMatchKeys.source(matchId), LiveMatchKeys.LIVE_TTL)

        // 사라진 골 = VAR 취소. 득점자 정보가 응답에서 없어졌으므로 키에서 되살린다
        for (goalKey in known - current.keys) {
            if (setOps.remove(key, goalKey) != 1L) continue
            val goal = LiveGoal.fromKey(goalKey) ?: continue
            chatService.publishLiveMessage(matchId, ChatMessageType.GOAL_CANCELLED, cancelText(goal), state)
            announced = true
            log.info("⚠️ [Live] VAR 취소 {} {} (matchId={})", goal.minute, goal.name, matchId)
        }
        return announced
    }

    /**
     * 소스가 바뀐 첫 턴 — 두 소스의 골 목록을 맞춰 본다.
     *
     * 골 키에는 선수 이름이 들어 있는데 네이버는 한글, FotMob은 영문이라 **같은 골이라도
     * 키가 다르다.** 그대로 비교하면 이미 알린 골 전부가 "새 골"로, 옛 키 전부가
     * "VAR 취소"로 쏟아진다. 그래서 이름 대신 **팀·분**으로 맞춘다.
     *
     * - 짝이 있으면 이미 알린 골 → 조용히 넘긴다
     * - 짝이 없으면 소스가 끊긴 동안 들어간 골 → 지금 알린다
     * - **취소는 이번 턴에 알리지 않는다.** 짝이 없는 옛 키는 VAR 취소일 수도, 두 소스의
     *   기록이 다른 것일 수도 있어 구분이 안 된다. 없는 취소를 알리는 쪽이 더 나쁘다
     */
    private fun realignAcrossSources(
        matchId: String,
        snapshot: LiveSnapshot,
        state: LiveMatchState,
        from: LiveSource
    ): Boolean {
        val key = LiveMatchKeys.goals(matchId)
        val setOps = redis.opsForSet()
        val unmatched = (setOps.members(key) ?: emptySet()).mapNotNull { LiveGoal.fromKey(it) }.toMutableList()

        val fresh = mutableListOf<LiveGoal>()
        for (goal in snapshot.goals) {
            // 짝은 하나씩만 소모한다 — 같은 팀이 2분 안에 두 골을 넣어도 하나로 뭉개지지 않는다
            val hit = unmatched
                .filter { it.isHome == goal.isHome && abs(it.totalMinute - goal.totalMinute) <= MINUTE_TOLERANCE }
                .minByOrNull { abs(it.totalMinute - goal.totalMinute) }
            if (hit == null) fresh += goal else unmatched.remove(hit)
        }

        // 집합을 새 소스의 키로 통째로 갈아 끼운다. 섞여 있으면 다음 턴 비교가 또 어긋난다
        redis.delete(key)
        if (snapshot.goals.isNotEmpty()) {
            setOps.add(key, *snapshot.goals.map { it.key }.toTypedArray())
            redis.expire(key, LiveMatchKeys.LIVE_TTL)
        }
        writeSource(matchId, snapshot.source)

        log.warn(
            "⚠️ [Live] 소스 전환 {} → {} — 기존 골 {}건 중 {}건 대조 성공, 새 골 {}건 (matchId={})",
            from, snapshot.source, snapshot.goals.size, snapshot.goals.size - fresh.size, fresh.size, matchId
        )

        for (goal in fresh) {
            chatService.publishLiveMessage(matchId, ChatMessageType.GOAL, goalText(goal), state)
            log.info("⚽ [Live] {} {} (소스 전환 중 감지, matchId={})", goal.minute, goal.name, matchId)
        }
        return fresh.isNotEmpty()
    }

    private fun readSource(matchId: String): LiveSource? =
        runCatching {
            redis.opsForValue().get(LiveMatchKeys.source(matchId))?.let { LiveSource.valueOf(it) }
        }.getOrNull()

    private fun writeSource(matchId: String, source: LiveSource) {
        runCatching {
            redis.opsForValue().set(LiveMatchKeys.source(matchId), source.name, LiveMatchKeys.LIVE_TTL)
        }
    }

    // ── 대상 경기 ────────────────────────────────────────────────────────

    /**
     * 지금 폴링할 경기들. 방이 열려 있는 구간(킥오프 −1h ~ +3h)만 본다.
     * 대부분의 시간에는 빈 목록이므로 [TICK_INTERVAL_MS]마다 DB를 치지 않도록 잠깐 캐시한다.
     */
    private fun activeMatches(): List<ActiveMatch> {
        val now = Instant.now()
        if (Duration.between(activeCachedAt, now) < ACTIVE_CACHE_TTL) return activeCache

        val at = OffsetDateTime.now()
        activeCache = runCatching {
            upcomingMatchRepository.findAllByOrderByMatchDateAsc()
                .mapNotNull { entity ->
                    val matchId = entity.matchId ?: return@mapNotNull null
                    val kickoff = MatchKickoff.parse(entity.matchDate) ?: return@mapNotNull null
                    ActiveMatch(matchId, kickoff)
                }
                .filter { !at.isBefore(it.kickoff.minus(ROOM_OPENS_BEFORE)) && !at.isAfter(it.kickoff.plus(ROOM_CLOSES_AFTER)) }
        }.getOrElse {
            log.warn("⚠️ [Live] 예정 경기 조회 실패: {}", it.message)
            emptyList()
        }
        activeCachedAt = now
        return activeCache
    }

    private fun applyBackoff(matchId: String, e: Exception) {
        val failures = failureCount.merge(matchId, 1) { a, b -> a + b } ?: 1
        val wait = minOf(
            BASE_BACKOFF.multipliedBy(1L shl minOf(failures - 1, MAX_BACKOFF_SHIFT)),
            MAX_BACKOFF
        )
        backoffUntil[matchId] = Instant.now().plus(wait)
        log.warn("⚠️ [Live] 조회 실패 {}회 — {}초 후 재시도 (matchId={}): {}", failures, wait.seconds, matchId, e.message)
    }

    companion object {
        /** 설계 5.4 — 4.2 실측에서 봇이 이미 시청자보다 느리므로 더 줄여도 체감 이득이 없다 */
        const val TICK_INTERVAL_MS = 5_000L

        private val PRE_KICKOFF_INTERVAL: Duration = Duration.ofSeconds(60)

        /** 주기(5초)보다 길어야 리더가 안정적으로 유지된다 */
        private val LOCK_TTL: Duration = Duration.ofSeconds(10)

        private val ROOM_OPENS_BEFORE: Duration = Duration.ofHours(1)
        private val ROOM_CLOSES_AFTER: Duration = Duration.ofHours(3)
        private val SETTLE_GRACE: Duration = Duration.ofMinutes(10)

        /**
         * 예정 경기 목록을 다시 읽는 간격. 3행짜리 조회라 짧아도 부담이 없고,
         * 새로 잡힌 경기를 그만큼 빨리 집는다 (리허설에서 특히 체감된다).
         */
        private val ACTIVE_CACHE_TTL: Duration = Duration.ofSeconds(20)

        private val BASE_BACKOFF: Duration = Duration.ofSeconds(10)
        private val MAX_BACKOFF: Duration = Duration.ofMinutes(2)
        private const val MAX_BACKOFF_SHIFT = 4
        private val RATE_LIMIT_BACKOFF: Duration = Duration.ofMinutes(1)

        // ── FotMob 폴백 (설계 8.3) ──

        /** 한 번씩 나는 타임아웃으로 소스를 갈아타지 않도록 두는 여유 */
        private const val FAILURES_BEFORE_FALLBACK = 3

        /** 한 번 내려가면 이만큼은 버틴다 — 두 소스를 오가며 골 정렬이 반복되는 것을 막는다 */
        private val FOTMOB_STICKY: Duration = Duration.ofMinutes(5)

        /** 응답이 70배 크고 CDN 캐시까지 붙어 있어 5초로 때릴 이유가 없다 */
        private val FOTMOB_INTERVAL: Duration = Duration.ofSeconds(15)

        /** 두 소스의 같은 골로 볼 분 오차. 제공사끼리 1분 정도는 흔히 어긋난다 */
        private const val MINUTE_TOLERANCE = 2

        /** 라틴 이름을 아직 못 채운 골이 있을 때 다시 물어보는 간격 — 골 알림에 실려야 하니 짧게 */
        private val LATIN_RETRY: Duration = Duration.ofSeconds(20)

        /** 시계 기준점이 당분간 안 바뀌는 구간의 간격 — 전반 한복판 */
        private val FOTMOB_CLOCK_RETRY: Duration = Duration.ofSeconds(60)

        /**
         * 교체만 보려고 FotMob을 다시 부르는 간격.
         *
         * 응답이 243KB라 촘촘히 때릴 수 없다. 게다가 FotMob은 캐시를 길게 잡는다 —
         * 2026-08-20 종료 경기 실측이 `max-age=900`이었다. 더 자주 불러도 같은 값이
         * 돌아올 뿐이라, 화면이 뒤늦게 받은 교체를 연출하지 않도록 막는 쪽이 본질이다.
         */
        private val SUBS_RETRY: Duration = Duration.ofSeconds(45)

        /**
         * 시계가 곧 바뀌는 구간의 간격. 이 구간에서는 늦게 아는 만큼 화면의 시계가
         * 그대로 틀린 값을 보여주므로 짧게 잡는다 ([clockRetryInterval]).
         */
        private val CLOCK_URGENT_RETRY: Duration = Duration.ofSeconds(15)

        /** 이 시점부터 하프타임을 지켜본다. 전반 추가시간이 아무리 짧아도 45분 전에는 안 끝난다 */
        private val HALFTIME_WATCH_FROM: Duration = Duration.ofMinutes(43)

        /**
         * FotMob이 준 기준점을 기존 값 **위에 얹는다.** 통째로 덮어쓰지 않는다.
         *
         * FotMob은 `halfs`를 부분적으로만 준다 — 2026-08-20 말라가전에서 `firstHalfEnded`가
         * 끝내 안 왔다. 그걸 그대로 덮어쓰면 네이버 `전반종료`로 메꿔 둔 값이 **매 조회마다
         * 지워지고 다시 만들어진다.** 하프타임에 시계가 멈추는 게 아니라 조회 주기마다
         * 뒤로 밀리고, 그때마다 상태 변화 브로드캐스트까지 나간다.
         *
         * 그래서 FotMob 값이 있으면 그것을 쓰고(정확도가 높다), 없으면 갖고 있던 것을 지킨다.
         */
        internal fun mergePeriods(current: LivePeriods?, fresh: LivePeriods): LivePeriods =
            LivePeriods(
                firstHalfStarted = fresh.firstHalfStarted ?: current?.firstHalfStarted,
                firstHalfEnded = fresh.firstHalfEnded ?: current?.firstHalfEnded,
                secondHalfStarted = fresh.secondHalfStarted ?: current?.secondHalfStarted
            )

        /**
         * 대기열에서 [cutoff]보다 오래된 것을 모두 꺼내고 **그중 가장 최신**을 돌려준다.
         *
         * 중간 것을 버려도 되는 이유: 스냅샷은 매번 **전체 상태**라 건너뛴 변화도 다음
         * 비교에서 그대로 잡힌다. 누적이 아니므로 놓치는 골이 없다.
         */
        internal fun drainDue(
            queue: ArrayDeque<Pair<Instant, LiveSnapshot>>,
            cutoff: Instant
        ): LiveSnapshot? {
            var due: LiveSnapshot? = null
            while (queue.isNotEmpty() && !queue.first().first.isAfter(cutoff)) {
                due = queue.removeFirst().second
            }
            return due
        }

        /**
         * 화면 시계를 [seconds]만큼 뒤로 민다.
         *
         * **봇이 중계보다 빠르면 실시간 정보가 아니라 스포일러다.** 2026-08-20 라리가
         * 말라가전에서 우리 시계가 쿠팡플레이보다 27~33초 빨랐다(8/15 프렌들리에서는 5초였다 —
         * 라리가는 스페인 제작 → 국제 전송 → 한국 재인코딩이라 중계 경로가 길다. 설계 4.3).
         *
         * 기준점을 통째로 미는 것이라 **전·후반·하프타임이 한꺼번에, 어긋남 없이** 밀린다.
         * 하프타임에 멈추는 값(`firstHalfEnded - firstHalfStarted`)은 차이라서 그대로다.
         *
         * 내부 판단(폴링 간격·하프타임 감시)에는 쓰지 않는다 — 밖으로 나가는 값에만 적용한다.
         * 그쪽까지 밀면 실제 경기 진행보다 30초 늦게 반응하게 된다.
         */
        internal fun delayed(periods: LivePeriods?, seconds: Long): LivePeriods? {
            if (periods == null || seconds == 0L) return periods
            fun push(t: OffsetDateTime?) = t?.plusSeconds(seconds)
            return LivePeriods(
                firstHalfStarted = push(periods.firstHalfStarted),
                firstHalfEnded = push(periods.firstHalfEnded),
                secondHalfStarted = push(periods.secondHalfStarted)
            )
        }

        /**
         * 스코어보드를 다시 그려야 할 만큼 상태가 바뀌었는지.
         *
         * 점수는 안 본다 — 점수가 바뀌는 순간은 곧 골이고, 그건 골 알림이 이미 나른다.
         * 여기서 잡고 싶은 건 **골 없이 바뀌는 것**뿐이다: 경기 시작·하프타임·후반 시작·종료.
         */
        internal fun clockChanged(previous: LiveMatchState, state: LiveMatchState): Boolean =
            previous.status != state.status ||
                !samePeriods(previous.periods, state.periods) ||
                // 교체는 골과 달리 채팅 메시지를 안 만든다. 여기서 안 잡으면 화면까지
                // 갈 방법이 없어서, 다음 골이 터질 때까지 보드가 모르고 있게 된다
                previous.substitutions != state.substitutions

        /**
         * 두 기준점이 **같은 순간**을 가리키는지.
         *
         * `==`로 비교하면 안 된다. [LiveMatchState.periods]의 한쪽은 방금 FotMob에서
         * 파싱한 값이고 다른 한쪽은 Redis에 넣었다 꺼낸 값인데, Jackson이 역직렬화하면서
         * 오프셋을 UTC로 옮겨 놓는다. `+02:00`과 `Z`는 같은 순간이어도 `OffsetDateTime.equals`가
         * 거짓이라, **조용한 5초마다 상태 변화가 일어난 것으로 잡힌다.**
         */
        private fun samePeriods(a: LivePeriods?, b: LivePeriods?): Boolean {
            if (a == null || b == null) return a == b
            return a.firstHalfStarted?.toInstant() == b.firstHalfStarted?.toInstant() &&
                a.firstHalfEnded?.toInstant() == b.firstHalfEnded?.toInstant() &&
                a.secondHalfStarted?.toInstant() == b.secondHalfStarted?.toInstant()
        }

        /**
         * FotMob에 시계를 다시 물어볼 간격.
         *
         * **화면의 시계가 실제로 틀어져 있는 구간에서만 촘촘히 간다.** 나머지 시간에는
         * 기준점이 안 바뀌므로 자주 물어도 같은 값만 돌아온다.
         *
         * - 기준점이 아직 없다 → 화면은 **예정** 킥오프로 세고 있어 실제 지연만큼 앞서 간다.
         *   8/15 마르세유전에서 초반 시계가 90~95초 빨랐던 구간이 정확히 여기다
         * - 하프타임 → 후반이 시작되는 순간을 늦게 알수록 시계가 멈춰 있는 시간이 길어진다
         * - 전반 [HALFTIME_WATCH_FROM] 이후 → 곧 하프타임이다. 늦게 알면 그만큼 더
         *   흘러갔다가 되돌아온다
         */
        internal fun clockRetryInterval(periods: LivePeriods?, now: Instant): Duration {
            val firstStart = periods?.firstHalfStarted ?: return CLOCK_URGENT_RETRY
            if (periods.firstHalfEnded != null) return CLOCK_URGENT_RETRY
            val elapsed = Duration.between(firstStart.toInstant(), now)
            return if (elapsed >= HALFTIME_WATCH_FROM) CLOCK_URGENT_RETRY else FOTMOB_CLOCK_RETRY
        }

        /**
         * 두 소스의 골 목록을 팀·분으로 맞춰 `골 키 → 라틴 이름` 표를 만든다.
         *
         * 짝은 하나씩만 소모한다 — 같은 팀이 2분 안에 두 골을 넣어도 한 이름으로 뭉개지지 않는다.
         * 짝이 없는 골은 그냥 빠진다(표에 안 들어간다). 잘못 붙이느니 비어 있는 편이 낫다 —
         * 엉뚱한 이름이 보드에 뜨면 그건 버그가 아니라 오보다.
         */
        internal fun pairLatinNames(korean: List<LiveGoal>, latin: List<LiveGoal>): Map<String, String> {
            val pool = latin.toMutableList()
            val paired = mutableMapOf<String, String>()

            for (goal in korean) {
                val hit = pool
                    .filter { it.isHome == goal.isHome && abs(it.totalMinute - goal.totalMinute) <= MINUTE_TOLERANCE }
                    .minByOrNull { abs(it.totalMinute - goal.totalMinute) }
                    ?: continue
                pool.remove(hit)
                paired[goal.key] = hit.name
            }
            return paired
        }

        /** 자책골을 표시하지 않으면 상대 선수를 우리 득점자로 읽히게 만든다 (설계 8.2) */
        internal fun goalText(goal: LiveGoal): String =
            "⚽ ${goal.minute} ${goal.name}" + if (goal.ownGoal) " (자책골)" else ""

        internal fun cancelText(goal: LiveGoal): String =
            "⚠️ VAR 판독 — ${goal.minute} ${goal.name}의 골이 취소되었습니다"
    }
}
