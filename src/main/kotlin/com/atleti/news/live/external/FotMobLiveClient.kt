package com.atleti.news.live.external

import com.atleti.news.live.domain.LivePeriods
import com.atleti.news.live.domain.LiveSubstitution
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import com.atleti.news.live.domain.LiveGoal
import com.atleti.news.live.domain.LiveSnapshot
import com.atleti.news.live.domain.LiveSource
import com.atleti.news.match.external.FotMobScrapeException

/**
 * 네이버가 깨졌을 때 쓰는 예비 소스 (docs/live-match-feed-design.md 8.3).
 *
 * **주 소스가 아니다.** 네이버보다 이런 점들이 나쁘다:
 * - 선수·팀 이름이 영문이다 (`Iván Martin`). 한글 채팅방에서 읽기 나쁘지만 없는 것보다는 낫다
 * - 응답이 70배 크다 (180KB 대 2.6KB). 그래서 폴링 주기도 네이버보다 길게 잡는다
 * - CDN 캐시가 붙어 있어 원본보다 늦다
 *
 * 대신 **matchId를 그대로 쓴다.** 채팅방 키가 곧 FotMob ID라 gameId 매핑이 필요 없어서,
 * "네이버 매핑에 실패한 경기"에서도 이 경로만은 살아 있다.
 *
 * 경기 종료 후 상세(xG·라인업·카드)는 [MatchScraperService]가 따로 처리한다 — 그쪽은
 * HTML의 `__NEXT_DATA__`를 긁고, 여기는 JSON API를 부른다.
 */
@Service
class FotMobLiveClient(
    private val objectMapper: ObjectMapper,
    @Value("\${atleti.source.fotmob-base-url}") baseUrl: String
) {
    private val log = LoggerFactory.getLogger(FotMobLiveClient::class.java)

    private val webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .defaultHeader(HttpHeaders.REFERER, "https://www.fotmob.com/")
        // 기본 상한(256KB)으로는 이벤트가 쌓인 경기에서 잘린다
        .codecs { it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) }
        .build()

    /**
     * @throws FotMobScrapeException 호출·파싱 실패
     */
    suspend fun fetchSnapshot(matchId: String): LiveSnapshot {
        val path = "/api/data/matchDetails?matchId=$matchId"
        val response = try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                webClient.get().uri(path).retrieve().toEntity(String::class.java).awaitSingle()
            }
        } catch (e: TimeoutCancellationException) {
            throw FotMobScrapeException("응답 시간 초과 ${REQUEST_TIMEOUT_MS}ms (matchId=$matchId)", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebClientResponseException) {
            throw FotMobScrapeException("HTTP ${e.statusCode.value()} (matchId=$matchId)", e)
        } catch (e: Exception) {
            throw FotMobScrapeException("요청 실패 (matchId=$matchId): ${e.message}", e)
        }

        // 캐시 정책은 9.2에서 확인할 항목이다. 라이브 중에도 길게 잡혀 있으면
        // 폴백이 그만큼 늦은 값을 주게 되므로 로그로 남겨 둔다
        logCacheControl(matchId, response.headers.cacheControl)

        val body = response.body ?: throw FotMobScrapeException("빈 응답 (matchId=$matchId)")
        return parseSnapshot(body)
    }

    /** 응답 본문 → [LiveSnapshot]. 네트워크와 분리해 두어 저장된 응답으로도 검증할 수 있다. */
    fun parseSnapshot(body: String): LiveSnapshot {
        val root = runCatching { objectMapper.readTree(body) }
            .getOrElse { throw FotMobScrapeException("JSON 파싱 실패", it) }

        val status = root.at("/header/status")
        val teams = root.at("/header/teams")
        if (!teams.isArray || teams.size() < 2) {
            throw FotMobScrapeException("header.teams 노드를 찾지 못했습니다")
        }

        val cancelled = status.at("/cancelled").asBoolean()
        val finished = status.at("/finished").asBoolean()
        val started = status.at("/started").asBoolean()

        return LiveSnapshot(
            source = LiveSource.FOTMOB,
            // 프론트 계약은 한글 상태 문구다. 네이버가 주던 값과 같은 어휘로 맞춘다
            status = when {
                cancelled -> "경기취소"
                finished -> "경기종료"
                started -> "경기중"
                else -> "경기전"
            },
            homeTeam = root.at("/general/homeTeam/name").asText().ifBlank { teams.at("/0/name").asText() },
            awayTeam = root.at("/general/awayTeam/name").asText().ifBlank { teams.at("/1/name").asText() },
            homeScore = teams.at("/0/score").asInt(),
            awayScore = teams.at("/1/score").asInt(),
            competition = root.at("/general/leagueName").asText(),
            goals = parseGoals(root.at("/content/matchFacts/events/events")),
            substitutions = parseSubstitutions(root.at("/content/matchFacts/events/events")),
            finished = finished || cancelled,
            periods = parsePeriods(status)
        )
    }

    /**
     * 경기 시계 기준점. `halfs`가 실제 전·후반 시작 시각을 준다 — 예정 킥오프와 달리
     * 지연·하프타임 길이·전반 추가시간이 전부 반영된 값이다.
     *
     * ```json
     * "halfs": { "firstHalfStarted": "09.08.2026 13:03:30", ... }
     * ```
     *
     * **타임존이 안 적혀 있다.** 실측값은 UTC+2(유럽 하계)였지만 그렇게 못 박으면
     * FotMob이 표기를 바꾸는 순간 시계가 몇 시간 틀어진다. 대신 같은 응답의
     * `utcTime`(예정 킥오프, UTC 명시)과 대조해 **오프셋을 역산한다.**
     */
    private fun parsePeriods(status: JsonNode): LivePeriods? {
        val halfs = status.at("/halfs")
        if (halfs.isMissingNode) return null

        val kickoff = runCatching { Instant.parse(status.at("/utcTime").asText()) }.getOrNull()
            ?: return null

        val firstHalf = halfs.at("/firstHalfStarted").asText().takeIf { it.isNotBlank() } ?: return null
        val offset = deriveOffset(firstHalf, kickoff) ?: return null

        fun at(field: String): OffsetDateTime? =
            halfs.at("/$field").asText().takeIf { it.isNotBlank() }
                ?.let { parseNaive(it) }
                ?.toInstant(offset)
                ?.atOffset(offset)

        return LivePeriods(
            firstHalfStarted = at("firstHalfStarted"),
            firstHalfEnded = at("firstHalfEnded"),
            secondHalfStarted = at("secondHalfStarted"),
            secondHalfEnded = at("secondHalfEnded")
        )
    }

    /**
     * 전반 시작 표기와 예정 킥오프(UTC)의 차이를 시간 단위로 반올림해 오프셋을 얻는다.
     *
     * 남는 잔차가 곧 "예정보다 얼마나 늦게 시작했나"인데, **경기는 늦어지지 일찍 시작하지
     * 않는다.** 그래서 허용 범위를 비대칭으로 잡는다 — 대칭(±30분)으로 두면 시간 반올림의
     * 잔차가 항상 그 안에 들어와 검증이 아무것도 걸러내지 못한다.
     */
    private fun deriveOffset(firstHalfStarted: String, kickoff: Instant): ZoneOffset? {
        val naive = parseNaive(firstHalfStarted) ?: return null

        val asUtc = naive.toInstant(ZoneOffset.UTC)
        val gapSeconds = Duration.between(kickoff, asUtc).seconds
        val hours = Math.round(gapSeconds / 3600.0).toInt()
        if (abs(hours) > MAX_OFFSET_HOURS) return null

        val offset = ZoneOffset.ofHours(hours)
        val delayMinutes = Duration.between(kickoff, naive.toInstant(offset)).toMinutes()
        if (delayMinutes < MIN_KICKOFF_DELAY_MIN || delayMinutes > MAX_KICKOFF_DELAY_MIN) {
            log.warn(
                "⚠️ [FotMob] halfs 시각을 해석하지 못했습니다 — 킥오프가 {}분 어긋납니다 ({})",
                delayMinutes, firstHalfStarted
            )
            return null
        }
        return offset
    }

    private fun parseNaive(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(raw.trim(), HALF_TIME_FORMAT) }.getOrNull()

    private fun parseGoals(events: JsonNode): List<LiveGoal> =
        events.asSequence()
            .filter { it.at("/type").asText() == "Goal" }
            .map { event ->
                LiveGoal(
                    name = event.at("/player/name").asText().ifBlank { event.at("/nameStr").asText() },
                    time = event.at("/time").asInt(),
                    // 추가시간. 실측한 응답에서는 전부 null이었고 값이 붙은 경우를 아직 못 봤다
                    addedTime = event.at("/overloadTime").asInt(0),
                    isHome = event.at("/isHome").asBoolean(),
                    // 이 필드는 false가 아니라 **null**로 온다. asBoolean()이 null을 false로 읽는다
                    ownGoal = event.at("/ownGoal").asBoolean()
                )
            }
            // 네이버 쪽과 같은 순서 보장을 준다 — 소스가 바뀌어도 목록 순서는 흔들리지 않아야 한다
            .sortedBy { it.totalMinute }
            .toList()

    /**
     * 선수 교체. **네이버에는 이 데이터가 아예 없어서** 여기가 유일한 소스다 (2026-08-20 확인).
     *
     * ```json
     * { "type": "Substitution", "time": 46, "isHome": true, "injuredPlayerOut": false,
     *   "swap": [ {"name": "Marcos Llorente"}, {"name": "Jorge Domínguez"} ] }
     * ```
     *
     * `swap`은 **[나가는 선수, 들어오는 선수] 순서**다. 둘 중 하나라도 이름이 비면 버린다 —
     * 화살표 한쪽이 빈 채로 보드에 뜨는 것보다 아예 안 뜨는 게 낫다.
     */
    private fun parseSubstitutions(events: JsonNode): List<LiveSubstitution> =
        events.asSequence()
            .filter { it.at("/type").asText() == "Substitution" }
            .mapNotNull { event ->
                val swap = event.at("/swap")
                val out = swap.at("/0/name").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val into = swap.at("/1/name").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LiveSubstitution(
                    minute = event.at("/time").asInt(),
                    addedTime = event.at("/overloadTime").asInt(0),
                    isHome = event.at("/isHome").asBoolean(),
                    playerOut = out,
                    playerIn = into,
                    injury = event.at("/injuredPlayerOut").asBoolean()
                )
            }
            .sortedBy { it.minute + it.addedTime }
            .toList()

    private fun logCacheControl(matchId: String, cacheControl: String?) {
        if (cacheControl.isNullOrBlank()) return
        val maxAge = MAX_AGE.find(cacheControl)?.groupValues?.get(1)?.toLongOrNull() ?: return
        if (maxAge > STALE_WARN_SECONDS) {
            log.warn(
                "⚠️ [FotMob] 캐시가 {}초로 잡혀 있습니다 — 폴백 값이 그만큼 늦을 수 있습니다 (matchId={})",
                maxAge, matchId
            )
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 응답이 커서 네이버(3초)보다 넉넉히 준다 */
        private const val REQUEST_TIMEOUT_MS = 6_000L

        private val MAX_AGE = Regex("""max-age=(\d+)""")
        private const val STALE_WARN_SECONDS = 60L

        /** `09.08.2026 13:03:30` */
        private val HALF_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

        /** 이보다 큰 오프셋이 나오면 형식을 잘못 읽은 것이다 */
        private const val MAX_OFFSET_HOURS = 14

        /** 예정보다 일찍 시작하는 경기는 없다. 음수가 크게 나오면 잘못 읽은 것이다 */
        private const val MIN_KICKOFF_DELAY_MIN = -5L

        /** 지연은 보통 2~5분. 30분을 넘으면 오프셋 해석이 틀렸다고 본다 */
        private const val MAX_KICKOFF_DELAY_MIN = 30L
    }
}
