package com.atleti.news.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 네이버 표기. `"아틀레티코"`로 찾으면 안 잡힌다. (설계 3.3)
 */
const val NAVER_ATLETI_NAME = "AT 마드리드"

/** 네이버 스포츠 호출·파싱 실패. "골이 아직 없음"과 구분해야 해서 예외로 올린다. */
open class NaverSportsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 429. 폴러가 주기를 늘려야 하므로 일반 실패와 구분한다. (설계 5.4) */
class NaverRateLimitException(message: String) : NaverSportsException(message)

/** `schedule/games/{gameId}` 응답에서 우리가 쓰는 것만 추린 것. (설계 3.1) */
data class NaverGame(
    val gameId: String,
    /** 오프셋 없는 KST 벽시계로 오므로 KST 기준으로 붙여 둔다. 파싱 실패 시 null */
    val kickoff: OffsetDateTime?,
    val categoryName: String,
    val stadium: String,
    val homeTeamName: String,
    val awayTeamName: String,
    val homeScore: Int,
    val awayScore: Int,
    /** 예: `RESULT` */
    val statusCode: String,
    /** 예: `경기종료` — 화면 표시에 그대로 쓸 수 있는 한글 */
    val statusInfo: String,
    val scorers: List<LiveGoal>
) {
    val involvesAtleti: Boolean
        get() = homeTeamName.contains(NAVER_ATLETI_NAME) || awayTeamName.contains(NAVER_ATLETI_NAME)

    /** 폴러가 소스와 무관하게 다루는 형태로. 상태 문구는 네이버 한글을 그대로 쓴다 */
    fun toSnapshot() = LiveSnapshot(
        source = LiveSource.NAVER,
        status = statusInfo,
        homeTeam = homeTeamName,
        awayTeam = awayTeamName,
        homeScore = homeScore,
        awayScore = awayScore,
        competition = categoryName,
        goals = scorers,
        finished = NaverSportsClient.isFinished(this)
    )
}

/**
 * 네이버 스포츠 내부 API 클라이언트. (설계 [docs/live-match-feed-design.md] 2·3장)
 *
 * 인증은 `Referer` 하나뿐이고 응답이 2.6KB로 작아 5초 폴링에 부담이 없다.
 * 캐시 헤더가 `no-cache`라 매 요청이 원본에 닿는다 — 폴링 주기가 곧 지연이 된다.
 *
 * 계약된 API가 아니라 예고 없이 바뀔 수 있다. 조용히 실패하지 않도록 실패는 모두
 * [NaverSportsException]으로 올리고, 폴백 여부는 호출자가 정한다. (설계 8.3)
 */
@Service
class NaverSportsClient(
    private val objectMapper: ObjectMapper,
    @Value("\${atleti.source.naver-base-url}") baseUrl: String
) {

    private val webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        // 이 헤더 하나가 인증 전부다. 빼면 막힌다.
        .defaultHeader(HttpHeaders.REFERER, "https://m.sports.naver.com/")
        .build()

    /**
     * 경기 상세. 폴러가 5초마다 부르는 유일한 경로다.
     *
     * @throws NaverRateLimitException 429를 받은 경우
     * @throws NaverSportsException 그 밖의 호출·파싱 실패
     */
    suspend fun fetchGame(gameId: String): NaverGame = parseGame(get("/schedule/games/$gameId"))

    /**
     * 해당 월의 gameId 목록.
     *
     * 목록 엔드포인트(`schedule/games?...`)는 파라미터 조합을 못 찾아 400만 내므로
     * 캘린더로 우회한다. (설계 3.3)
     *
     * @param yearMonth `2026-08` 형식
     */
    suspend fun fetchMonthlyGameIds(
        yearMonth: String,
        categoryId: String = LALIGA_CATEGORY_ID
    ): List<String> {
        val path = "/schedule/calendar" +
            "?upperCategoryId=$UPPER_CATEGORY_ID&categoryId=$categoryId&yearMonth=$yearMonth"
        val dates = readTree(get(path), path).at("/result/dates")
        if (!dates.isArray) throw NaverSportsException("result.dates 노드를 찾지 못했습니다 (yearMonth=$yearMonth)")

        return dates.flatMap { date -> date.at("/gameIds").map { it.asText() } }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * 해당 월 아틀레티 경기를 킥오프 순으로 돌려준다. matchId ↔ gameId 매핑의 재료다. (설계 7장)
     *
     * 캘린더에는 팀명이 없어 gameId를 하나씩 열어봐야 한다. 하루 한 번 도는 경로라
     * 이 비용은 감수하되, 1GB VM에서 비공식 API를 때리는 것이므로 한 번에 몰아치지는 않는다.
     * 개별 경기 조회 실패는 건너뛴다 — 한 경기 때문에 매핑 전체를 잃을 이유가 없다.
     */
    suspend fun findAtletiGames(
        yearMonth: String,
        categoryId: String = LALIGA_CATEGORY_ID
    ): List<NaverGame> {
        val ids = fetchMonthlyGameIds(yearMonth, categoryId)
        val found = mutableListOf<NaverGame>()

        ids.chunked(DETAIL_CONCURRENCY).forEachIndexed { index, chunk ->
            if (index > 0) delay(DETAIL_CHUNK_DELAY_MS)
            val games = coroutineScope {
                chunk.map { id -> async { runCatching { fetchGame(id) }.getOrNull() } }.awaitAll()
            }
            found += games.filterNotNull().filter { it.involvesAtleti }
        }

        return found.sortedBy { it.kickoff }
    }

    /**
     * 응답 본문 → [NaverGame]. 네트워크와 분리해 두어 저장된 응답으로도 검증할 수 있다.
     */
    fun parseGame(body: String): NaverGame {
        val game = readTree(body, "game").at("/result/game")
        if (game.isMissingNode || game.at("/gameId").asText().isBlank()) {
            throw NaverSportsException("result.game 노드를 찾지 못했습니다")
        }

        return NaverGame(
            gameId = game.at("/gameId").asText(),
            kickoff = parseKickoff(game.at("/gameDateTime").asText()),
            categoryName = game.at("/categoryName").asText(),
            stadium = game.at("/stadium").asText(),
            homeTeamName = game.at("/homeTeamName").asText(),
            awayTeamName = game.at("/awayTeamName").asText(),
            homeScore = game.at("/homeTeamScore").asInt(),
            awayScore = game.at("/awayTeamScore").asInt(),
            statusCode = game.at("/statusCode").asText(),
            statusInfo = game.at("/statusInfo").asText(),
            scorers = parseScorers(game.at("/scorers"))
        )
    }

    /**
     * `{home:[...], away:[...]}` 를 isHome이 붙은 평평한 목록으로 편다.
     *
     * **시간순으로 정렬해서 내보낸다.** 받은 순서대로 두면 "홈 전부 → 원정 전부"라
     * 스코어보드와 골 목록이 경기 흐름과 다르게 보인다. 한 턴에 여러 골이 잡혔을 때
     * 알림이 나가는 순서도 이 순서를 따르므로, 여기서 맞춰 두면 소비하는 쪽이 다시
     * 정렬할 일이 없다.
     */
    private fun parseScorers(node: JsonNode): List<LiveGoal> =
        listOf("home" to true, "away" to false).flatMap { (side, isHome) ->
            node.at("/$side").map { goal ->
                LiveGoal(
                    name = goal.at("/playerName").asText(),
                    time = goal.at("/time").asInt(),
                    addedTime = goal.at("/addedTime").asInt(),
                    isHome = isHome,
                    // 무시하면 상대 선수를 우리 득점자로 표시하는 사고가 난다 (설계 8.2)
                    ownGoal = goal.at("/ownGoal").asBoolean()
                )
            }
        }.sortedBy { it.totalMinute }

    /** `2026-08-15T00:30:00` — 오프셋이 없지만 KST 벽시계다. */
    private fun parseKickoff(raw: String): OffsetDateTime? = runCatching {
        LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(KST).toOffsetDateTime()
    }.getOrNull()

    private fun readTree(body: String, what: String): JsonNode =
        runCatching { objectMapper.readTree(body) }
            .getOrElse { throw NaverSportsException("JSON 파싱 실패 ($what)", it) }

    private suspend fun get(path: String): String =
        try {
            // 5초 폴링이므로 그보다 짧게 끊어야 다음 턴을 밀지 않는다
            withTimeout(REQUEST_TIMEOUT_MS) {
                webClient.get().uri(path).retrieve().awaitBody<String>()
            }
        } catch (e: TimeoutCancellationException) {
            throw NaverSportsException("응답 시간 초과 ${REQUEST_TIMEOUT_MS}ms ($path)", e)
        } catch (e: CancellationException) {
            throw e // 폴러 종료로 인한 취소는 장애가 아니다
        } catch (e: WebClientResponseException.TooManyRequests) {
            throw NaverRateLimitException("429 Too Many Requests ($path)")
        } catch (e: WebClientResponseException) {
            throw NaverSportsException("HTTP ${e.statusCode.value()} ($path)", e)
        } catch (e: Exception) {
            throw NaverSportsException("요청 실패 ($path): ${e.message}", e)
        }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val UPPER_CATEGORY_ID = "wfootball"

        /** 라리가는 `primera`다. 프렌들리도 이 캘린더에 함께 잡힌다. (설계 3.3) */
        const val LALIGA_CATEGORY_ID = "primera"

        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val DETAIL_CONCURRENCY = 5
        private const val DETAIL_CHUNK_DELAY_MS = 200L

        private val KST = ZoneId.of("Asia/Seoul")

        private val FINISHED_CODES = setOf("RESULT", "FINAL", "END", "CANCEL", "CANCELED", "POSTPONE", "POSTPONED")
        private val FINISHED_LABELS = listOf("종료", "취소", "연기", "몰수")

        /**
         * 폴링을 멈춰도 되는 상태인지. 라이브 중의 실제 `statusCode` 값은 아직 실측 전이라
         * (설계 9.2) 코드와 한글 표기를 함께 보고 넉넉히 잡는다.
         * 늦게 멈추는 쪽이 알림을 놓치지 않는다.
         */
        internal fun isFinished(game: NaverGame): Boolean =
            game.statusCode.uppercase() in FINISHED_CODES ||
                FINISHED_LABELS.any { game.statusInfo.contains(it) }
    }
}
