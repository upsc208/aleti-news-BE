package com.atleti.news.live.external

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
import com.atleti.news.live.domain.LiveGoal
import com.atleti.news.live.domain.LiveSnapshot
import com.atleti.news.live.domain.LiveSource

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
        finished = NaverSportsClient.isFinished(this),
        halftime = NaverSportsClient.isHalftime(this),
        secondHalf = NaverSportsClient.isSecondHalf(this),
        statusCode = statusCode
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

        /**
         * 끝났다고 볼 한글 표기. **부분 일치로 보면 안 된다.**
         *
         * 2026-08-20 말라가전에서 네이버의 하프타임 표기가 `전반종료`였는데,
         * `contains("종료")`가 이걸 경기 종료로 읽었다. 폴러는 전반이 끝나자마자 이 경기를
         * 끝난 것으로 표시하고 폴링을 멈췄고, **후반 골이 통째로 안 나갔다.**
         * 하프타임·후반 시계가 죽은 것도 같은 이유다 (docs/round1.md).
         *
         * 그래서 공백만 지우고 **정확히 일치할 때만** 끝난 것으로 본다. 모르는 표기가
         * 들어오면 안 끝난 것으로 보는데, 그래도 폴러는 킥오프 +3시간에 어차피 멈추므로
         * 늦게 멈추는 쪽이 안전하다 — 일찍 멈추면 이번처럼 경기가 통째로 날아간다.
         */
        private val FINISHED_LABELS = setOf("경기종료", "종료", "경기취소", "취소", "연기", "몰수")

        /** 하프타임 표기 (2026-08-20 실측: `전반종료`) */
        private val HALFTIME_LABELS = setOf("전반종료", "하프타임", "휴식")

        /**
         * 후반이 돌고 있다는 표기.
         *
         * **라리가 표기는 분 단위다** — 2026-08-30 세비야전 로그에서 확인했다:
         * `전반 3'` → `전반종료` → `후반 1'` → `경기종료`. 설계 문서가 예로 들던 `경기중`은
         * 이 대회에서 한 번도 안 나왔다. 그래서 정확 일치가 아니라 `후반`을 품었는지로 본다.
         *
         * 다른 대회가 `경기중`으로 줄 가능성은 남아 있어 함께 받아 둔다. `전반종료`는
         * `전반`을 품고 있으니 여기 걸리지 않는다.
         */
        private const val SECOND_HALF_MARK = "후반"
        private val IN_PLAY_LABELS = setOf("경기중")

        /** 끝을 가리키는 조각. 후반 판정에서만 부분 일치로 쓴다 ([isSecondHalf]) */
        private const val END_MARK = "종료"

        private fun normalize(statusInfo: String) = statusInfo.replace(" ", "").trim()

        /**
         * 폴링을 멈춰도 되는 상태인지.
         *
         * **하프타임이면 무조건 아니다.** statusCode보다 이걸 먼저 본다 — 8/20 경기에서
         * 하프타임의 실제 `statusCode`가 무엇이었는지 기록이 없어서, 그게 종료 코드였을
         * 가능성을 아직 못 지웠다. 표기가 `전반종료`인데 코드 때문에 또 멈추면 같은 사고가 난다.
         */
        internal fun isFinished(game: NaverGame): Boolean {
            if (normalize(game.statusInfo) in HALFTIME_LABELS) return false
            return game.statusCode.uppercase() in FINISHED_CODES ||
                normalize(game.statusInfo) in FINISHED_LABELS
        }

        /**
         * 하프타임인지. **네이버가 FotMob보다 먼저, 그리고 확실하게 알려준다.**
         *
         * 8/20 경기에서 FotMob의 `halfs.firstHalfEnded`는 전반 종료 15분이 지나도록
         * 끝내 안 왔다(CDN 캐시로 추정 — 설계 8.6). 그동안 화면 시계는 계속 돌았다.
         * 그래서 시계 기준점을 FotMob에만 의존하지 않고 이 신호로도 만든다.
         */
        internal fun isHalftime(game: NaverGame): Boolean =
            normalize(game.statusInfo) in HALFTIME_LABELS

        /**
         * 후반이 시작됐는지. **"하프타임이 아니다"의 반대말이 아니다.**
         *
         * 8/30 세비야전에서 전반 종료를 FotMob이 먼저 알려줬는데 네이버는 그때 아직
         * `전반 50'`이었다. "하프타임이 아니면 후반"으로 보던 판정이 그 순간 걸려서
         * 전반 종료 12초 만에 후반 시작 시각이 찍혔다 (docs/round3.md ③).
         *
         * 그래서 **후반이라고 말할 때만** 참이다.
         *
         * **여기서는 `종료`를 품은 표기를 부분 일치로 걷어낸다.** 종료 판정에서 부분 일치를
         * 금지한 것([FINISHED_LABELS])과 반대로 보이지만 묻는 질문이 다르다. 저쪽은
         * "경기가 끝났나"라 잘못 참이면 경기가 통째로 날아가지만, 이쪽은 "후반이 시작됐나"라
         * 잘못 참이면 시계 기준점이 엉뚱한 시각에 찍힌다. `후반종료`처럼 모르는 종료 표기를
         * 후반으로 읽으면 **경기 끝에 시계가 45:00으로 되돌아간다.** 여기서는 안 찍는 쪽이
         * 안전하다 — 시계는 멈춰 있다가 FotMob 값이 오면 맞춰진다.
         */
        internal fun isSecondHalf(game: NaverGame): Boolean {
            if (isFinished(game) || isHalftime(game)) return false
            val label = normalize(game.statusInfo)
            if (label.contains(END_MARK)) return false
            return label.contains(SECOND_HALF_MARK) || label in IN_PLAY_LABELS
        }
    }
}
