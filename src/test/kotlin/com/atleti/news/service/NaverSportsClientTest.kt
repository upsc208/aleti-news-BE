package com.atleti.news.service

import com.atleti.news.config.JacksonConfig
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 네이버 응답 파싱 검증. 본문은 2026-08-10에 실제로 받은 응답에서 우리가 쓰는 필드만 추린 것이다.
 * (설계 3.1 / 구현 순서 1단계 "아틀레티 경기 스코어·득점자 파싱 성공")
 */
class NaverSportsClientTest {

    // 파싱만 검증하므로 주소는 쓰이지 않는다
    private val client = NaverSportsClient(JacksonConfig().objectMapper(), "https://api-gw.sports.naver.com")

    /** 실측 응답 — 08-15 마르세유 vs AT 마드리드 (원정 1골) */
    private val marseilleResponse = """
        {
          "result": {
            "game": {
              "gameId": "QyRcDHa8DA07hWz",
              "gameDateTime": "2026-08-15T00:30:00",
              "categoryName": "클럽 프렌들리",
              "stadium": "스타드 벨로드롬",
              "homeTeamName": "마르세유",  "homeTeamScore": 0,
              "awayTeamName": "AT 마드리드", "awayTeamScore": 1,
              "statusCode": "RESULT",
              "statusInfo": "경기종료",
              "scorers": {
                "home": [],
                "away": [ { "time": 43, "addedTime": 0, "playerName": "호르헤 도밍게스", "ownGoal": false } ]
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `경기 스코어와 득점자를 파싱한다`() {
        val game = client.parseGame(marseilleResponse)

        assertEquals("QyRcDHa8DA07hWz", game.gameId)
        assertEquals("마르세유", game.homeTeamName)
        assertEquals("AT 마드리드", game.awayTeamName)
        assertEquals(0, game.homeScore)
        assertEquals(1, game.awayScore)
        assertEquals("RESULT", game.statusCode)
        assertEquals("경기종료", game.statusInfo)
        assertEquals("클럽 프렌들리", game.categoryName)
        assertEquals("스타드 벨로드롬", game.stadium)

        val scorer = game.scorers.single()
        assertEquals("호르헤 도밍게스", scorer.name)
        assertEquals(43, scorer.time)
        assertEquals(0, scorer.addedTime)
        assertFalse(scorer.isHome)
        assertFalse(scorer.ownGoal)
    }

    @Test
    fun `킥오프는 오프셋 없이 와도 KST로 붙인다`() {
        // "2026-08-15T00:30:00" 을 UTC로 읽으면 한국 시간 09:30이 되어 방이 9시간 늦게 열린다
        assertEquals(
            OffsetDateTime.parse("2026-08-15T00:30:00+09:00"),
            client.parseGame(marseilleResponse).kickoff
        )
    }

    @Test
    fun `홈 원정 득점자를 한 목록으로 펴고 추가시간과 자책골을 살린다`() {
        val game = client.parseGame(
            """
            {
              "result": { "game": {
                "gameId": "Z5BQDTTiMjv6iBO",
                "gameDateTime": "2026-08-20T04:00:00",
                "categoryName": "라리가", "stadium": "리야드 에어 메트로폴리타노",
                "homeTeamName": "AT 마드리드", "homeTeamScore": 2,
                "awayTeamName": "말라가", "awayTeamScore": 1,
                "statusCode": "STARTED", "statusInfo": "경기중",
                "scorers": {
                  "home": [ { "time": 12, "addedTime": 0, "playerName": "훌리안 알바레스", "ownGoal": false },
                            { "time": 45, "addedTime": 2, "playerName": "안토니오 힐", "ownGoal": false } ],
                  "away": [ { "time": 70, "addedTime": 0, "playerName": "로빈 르 노르망", "ownGoal": true } ]
                }
              } }
            }
            """.trimIndent()
        )

        // 네이버는 홈 목록·원정 목록을 따로 주지만 우리는 시간순으로 편다 —
        // 받은 순서(홈 12′·45+2′ → 원정 70′)를 그대로 두면 골 목록이 경기 흐름과 다르게 보인다
        assertEquals(listOf(12, 47, 70), game.scorers.map { it.totalMinute })
        assertEquals(listOf(true, true, false), game.scorers.map { it.isHome })
        assertEquals(2, game.scorers[1].addedTime)

        // 자책골을 놓치면 상대 선수를 우리 득점자로 표시하는 사고가 난다 (설계 8.2)
        val ownGoal = game.scorers.single { it.ownGoal }
        assertEquals("로빈 르 노르망", ownGoal.name)
        assertFalse(ownGoal.isHome)
    }

    @Test
    fun `골 키는 같은 골에 항상 같은 값을 준다`() {
        // 이 키가 흔들리면 재시작 때 이미 알린 골을 다시 알린다 (설계 5-3)
        val first = client.parseGame(marseilleResponse).scorers.single()
        val again = client.parseGame(marseilleResponse).scorers.single()

        assertEquals("A|43+0|호르헤 도밍게스|false", first.key)
        assertEquals(first.key, again.key)
    }

    @Test
    fun `서로 다른 골은 키가 겹치지 않는다`() {
        val base = LiveGoal("훌리안 알바레스", 45, 0, isHome = true, ownGoal = false)
        val keys = listOf(
            base,
            base.copy(addedTime = 2),          // 45분과 45+2분은 다른 골이다
            base.copy(isHome = false),
            base.copy(ownGoal = true),
            base.copy(name = "안토니오 힐")
        ).map { it.key }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `분 표기는 추가시간이 있을 때만 더한다`() {
        assertEquals("43'", LiveGoal("도밍게스", 43, 0, isHome = false, ownGoal = false).minute)
        assertEquals("45+2'", LiveGoal("힐", 45, 2, isHome = true, ownGoal = false).minute)
    }

    @Test
    fun `양 팀 득점자를 시간순으로 섞어 준다`() {
        // 홈이 먼저 넣고 원정이 뒤에 넣은 경기 — 팀별로 뭉쳐 나오면 안 된다
        val game = client.parseGame(
            """
            {
              "result": { "game": {
                "gameId": "g", "gameDateTime": "2026-08-20T04:00:00",
                "categoryName": "라리가", "stadium": "s",
                "homeTeamName": "AT 마드리드", "homeTeamScore": 2,
                "awayTeamName": "말라가", "awayTeamScore": 2,
                "statusCode": "STARTED", "statusInfo": "경기중",
                "scorers": {
                  "home": [ { "time": 3, "addedTime": 0, "playerName": "알바레스", "ownGoal": false },
                            { "time": 58, "addedTime": 0, "playerName": "코케", "ownGoal": false } ],
                  "away": [ { "time": 24, "addedTime": 0, "playerName": "쿠보", "ownGoal": false },
                            { "time": 45, "addedTime": 5, "playerName": "멘데스", "ownGoal": false } ]
                }
              } }
            }
            """.trimIndent()
        )

        assertEquals(listOf("알바레스", "쿠보", "멘데스", "코케"), game.scorers.map { it.name })
    }

    @Test
    fun `아틀레티 경기인지 팀명으로 판별한다`() {
        assertTrue(client.parseGame(marseilleResponse).involvesAtleti)

        val other = client.parseGame(
            marseilleResponse
                .replace("\"AT 마드리드\"", "\"레알 마드리드\"")
        )
        assertFalse(other.involvesAtleti)
    }

    @Test
    fun `경기 전이라 득점자가 비어 있어도 파싱된다`() {
        val game = client.parseGame(
            """
            {
              "result": { "game": {
                "gameId": "nSpr6NeOefWQhYU",
                "gameDateTime": "2026-08-24T00:00:00",
                "categoryName": "라리가", "stadium": "리야드 에어 메트로폴리타노",
                "homeTeamName": "AT 마드리드", "homeTeamScore": 0,
                "awayTeamName": "비야레알", "awayTeamScore": 0,
                "statusCode": "BEFORE", "statusInfo": "경기전"
              } }
            }
            """.trimIndent()
        )

        // scorers 키 자체가 없어도 빈 목록이어야 한다. 여기서 터지면 킥오프 전 스코어보드를 못 그린다
        assertTrue(game.scorers.isEmpty())
        assertEquals("경기전", game.statusInfo)
    }

    @Test
    fun `응답 모양이 바뀌면 조용히 넘어가지 않고 예외를 낸다`() {
        // 비공식 API라 예고 없이 바뀐다. 0-0으로 조용히 흘러가면 골 알림이 통째로 사라진다 (설계 8.3)
        assertFailsWith<NaverSportsException> { client.parseGame("""{"result":{}}""") }
        assertFailsWith<NaverSportsException> { client.parseGame("<html>error</html>") }
    }
}
