package com.atleti.news.match.external

import com.atleti.news.match.external.MatchScraperService.Companion.parseKickoff
import com.atleti.news.match.external.MatchScraperService.Companion.parseLastMatch
import com.atleti.news.match.external.MatchScraperService.Companion.selectUpcoming
import com.atleti.news.match.external.MatchScraperService.Companion.toUpcomingEntity
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FotMob 응답 파싱 검증.
 *
 * 전에는 이 코드에 테스트를 붙일 수 없었다. HTTP 요청과 파싱과 저장이 한 메서드에 붙어 있어
 * **테스트 더블을 끼울 이음매가 없었고**, 검증하려면 실제 FotMob을 불러야 했다
 * (docs/architecture.md 3.3). 파싱을 순수 함수로 떼어 내면서 저장해 둔 응답으로 검증할 수 있게 됐다 —
 * `NaverSportsClient.parseGame`이 이미 쓰던 방식이다.
 *
 * 여기서 지키는 것은 **이미 한 번 틀렸던 판단들**이다. 홈/원정을 팀 이름으로 정하다 뒤집혔고,
 * 킥오프 형식이 두 가지로 오고, 저장 형식이 `ChatService`의 파서와 맞아야 한다.
 */
class MatchScraperParsingTest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private fun json(s: String) = mapper.readTree(s.trimIndent())

    // ── 홈/원정 판정 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("홈/원정은 팀 이름이 아니라 팀 ID로 정한다")
    fun testIsHomeByTeamId() {
        // 상대가 Real Madrid면 이름에 madrid가 들어간다. 문자열 비교로 정하면 뒤집힌다
        val node = json(
            """
            {
              "id": "4506789",
              "home": { "id": "8633", "name": "Real Madrid" },
              "away": { "id": "9906", "name": "Atletico Madrid" },
              "opponent": { "name": "Real Madrid" },
              "tournament": { "name": "LaLiga" }
            }
            """
        )

        val result = toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z"))
        assertEquals(false, result.isHome, "아틀레티가 away인데 home으로 잡혔다")
        assertEquals("Real Madrid", result.opponent)
    }

    @Test
    @DisplayName("아틀레티가 홈이면 isHome이 참이다")
    fun testIsHomeWhenAtletiIsHome() {
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9906", "name": "Atletico Madrid" },
              "away": { "id": "8371", "name": "Rayo Vallecano" },
              "opponent": { "name": "Rayo Vallecano" }, "tournament": { "name": "LaLiga" }
            }
            """
        )
        assertTrue(toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z")).isHome)
    }

    @Test
    @DisplayName("opponent 이름이 비면 반대편 팀 이름으로 메꾼다")
    fun testOpponentFallback() {
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9906", "name": "Atletico Madrid" },
              "away": { "id": "8371", "name": "Villarreal" },
              "opponent": { "name": "" }, "tournament": { "name": "LaLiga" }
            }
            """
        )
        assertEquals("Villarreal", toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z")).opponent)
    }

    @Test
    @DisplayName("대회명이 비면 기본값을 넣는다")
    fun testTournamentFallback() {
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9906", "name": "Atletico" },
              "away": { "id": "2", "name": "X" }, "opponent": { "name": "X" },
              "tournament": { "name": "" }
            }
            """
        )
        assertEquals("Tournament", toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z")).tournament)
    }

    // ── 저장 형식 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("킥오프는 ChatService가 읽는 KST 형식으로 저장된다")
    fun testKickoffStoredAsKstIsoOffset() {
        // ChatService.findKickoffTime과 MatchKickoff.parse가 이 형식을 기대한다.
        // 여기서 형식이 어긋나면 채팅방이 열리지 않는다
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9906", "name": "A" }, "away": { "id": "2", "name": "B" },
              "opponent": { "name": "B" }, "tournament": { "name": "LaLiga" }
            }
            """
        )

        val result = toUpcomingEntity(node, Instant.parse("2026-08-23T15:00:00Z"))
        assertEquals("2026-08-24T00:00:00+09:00", result.matchDate)
    }

    // ── 킥오프 파싱 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("킥오프 형식 두 가지를 모두 읽는다")
    fun testKickoffFormats() {
        val expected = Instant.parse("2026-08-23T15:00:00Z")
        assertEquals(expected, parseKickoff("2026-08-23T15:00:00Z"))
        // FotMob이 공백으로 주는 경우가 있다
        assertEquals(expected, parseKickoff("2026-08-23 15:00:00Z"))
    }

    @Test
    @DisplayName("못 읽는 킥오프는 null — 지어내지 않는다")
    fun testKickoffUnparsable() {
        assertNull(parseKickoff(""))
        assertNull(parseKickoff("나중에"))
    }

    // ── 예정 경기 선별 ───────────────────────────────────────────────────

    private val fixtures = """
        [
          { "id": "past",      "status": { "utcTime": "2026-08-01T15:00:00Z", "finished": true,  "cancelled": false },
            "home": { "id": "9906", "name": "A" }, "away": { "id": "2", "name": "B" }, "opponent": { "name": "B" }, "tournament": { "name": "L" } },
          { "id": "cancelled", "status": { "utcTime": "2026-09-10T15:00:00Z", "finished": false, "cancelled": true },
            "home": { "id": "9906", "name": "A" }, "away": { "id": "3", "name": "C" }, "opponent": { "name": "C" }, "tournament": { "name": "L" } },
          { "id": "third",     "status": { "utcTime": "2026-09-30T15:00:00Z", "finished": false, "cancelled": false },
            "home": { "id": "9906", "name": "A" }, "away": { "id": "4", "name": "D" }, "opponent": { "name": "D" }, "tournament": { "name": "L" } },
          { "id": "first",     "status": { "utcTime": "2026-09-05T15:00:00Z", "finished": false, "cancelled": false },
            "home": { "id": "9906", "name": "A" }, "away": { "id": "5", "name": "E" }, "opponent": { "name": "E" }, "tournament": { "name": "L" } },
          { "id": "broken",    "status": { "utcTime": "", "finished": false, "cancelled": false },
            "home": { "id": "9906", "name": "A" }, "away": { "id": "6", "name": "F" }, "opponent": { "name": "F" }, "tournament": { "name": "L" } },
          { "id": "second",    "status": { "utcTime": "2026-09-20T15:00:00Z", "finished": false, "cancelled": false },
            "home": { "id": "9906", "name": "A" }, "away": { "id": "7", "name": "G" }, "opponent": { "name": "G" }, "tournament": { "name": "L" } }
        ]
    """

    @Test
    @DisplayName("끝난 경기·취소된 경기·과거 경기를 버리고 킥오프 순으로 준다")
    fun testSelectUpcomingOrdersAndFilters() {
        val now = Instant.parse("2026-08-26T00:00:00Z")

        val result = selectUpcoming(json(fixtures), now, limit = 3)

        // 배열 순서가 아니라 킥오프 순이어야 한다 — 응답이 정렬돼 온다는 보장이 없다
        assertEquals(listOf("E", "G", "D"), result.map { it.opponent })
    }

    @Test
    @DisplayName("킥오프를 못 읽은 경기는 목록에서 빠진다")
    fun testSelectUpcomingDropsUnparsable() {
        val result = selectUpcoming(json(fixtures), Instant.parse("2026-08-26T00:00:00Z"), limit = 10)
        // 방 오픈 시각을 계산할 수 없어 채팅방을 못 여는 경기다
        assertTrue(result.none { it.opponent == "F" })
        assertEquals(3, result.size, "유효한 예정 경기는 셋뿐이다")
    }

    @Test
    @DisplayName("limit만큼만 준다")
    fun testSelectUpcomingRespectsLimit() {
        val result = selectUpcoming(json(fixtures), Instant.parse("2026-08-26T00:00:00Z"), limit = 1)
        assertEquals(listOf("E"), result.map { it.opponent })
    }

    // ── 최근 경기 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("최근 경기를 KST 시각으로 바꿔 담는다")
    fun testParseLastMatch() {
        val node = json(
            """
            {
              "id": "5868022",
              "home": { "name": "Atletico Madrid", "score": 2 },
              "away": { "name": "Villarreal", "score": 2 },
              "tournament": { "name": "LaLiga" },
              "status": { "utcTime": "2026-08-23T15:00:00Z" }
            }
            """
        )

        val result = parseLastMatch(node, mapOf("xg" to "{}", "possession" to "55"))

        assertEquals("5868022", result.matchId)
        assertEquals(2, result.homeScore)
        assertEquals(2, result.awayScore)
        assertTrue(result.isHome, "홈팀 이름이 Atletico면 홈 경기다")
        assertEquals("2026-08-24T00:00:00+09:00", result.matchDate)
        assertEquals("55", result.possession)
    }

    @Test
    @DisplayName("악센트가 붙은 Atlético도 홈으로 읽는다")
    fun testParseLastMatchAccentedName() {
        val node = json(
            """
            {
              "id": "1", "home": { "name": "Atlético de Madrid", "score": 1 },
              "away": { "name": "Sevilla", "score": 0 },
              "tournament": { "name": "LaLiga" }, "status": { "utcTime": "2026-08-30T19:00:00Z" }
            }
            """
        )
        assertTrue(parseLastMatch(node, emptyMap()).isHome)
    }

    @Test
    @DisplayName("시각을 못 읽으면 원문을 그대로 둔다 — 지어내지 않는다")
    fun testParseLastMatchKeepsUnparsableTime() {
        val node = json(
            """
            {
              "id": "1", "home": { "name": "Atletico", "score": 0 }, "away": { "name": "X", "score": 0 },
              "tournament": { "name": "L" }, "status": { "utcTime": "곧" }
            }
            """
        )
        assertEquals("곧", parseLastMatch(node, emptyMap()).matchDate)
    }
}
