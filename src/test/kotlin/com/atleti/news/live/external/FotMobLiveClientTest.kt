package com.atleti.news.live.external

import com.atleti.news.common.JacksonConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.atleti.news.live.domain.LiveMatchPoller
import com.atleti.news.live.domain.LiveSource
import com.atleti.news.match.external.FotMobScrapeException

/**
 * FotMob 폴백 응답 파싱 (docs/live-match-feed-design.md 8.3).
 * 본문은 2026-08-11에 `/api/data/matchDetails?matchId=5862945`(맨시티 3-1 아틀레티)에서
 * 실제로 받은 응답에서 우리가 쓰는 필드만 추린 것이다.
 */
class FotMobLiveClientTest {

    // 파싱만 검증하므로 주소는 쓰이지 않는다
    private val client = FotMobLiveClient(JacksonConfig().objectMapper(), "https://www.fotmob.com")

    private val finishedResponse = """
        {
          "general": {
            "homeTeam": { "name": "Manchester City", "id": 8456 },
            "awayTeam": { "name": "Atletico Madrid", "id": 9906 },
            "started": true, "finished": true
          },
          "header": {
            "status": { "started": true, "finished": true, "cancelled": false, "scoreStr": "3 - 1" },
            "teams": [ { "name": "Manchester City", "score": 3 }, { "name": "Atletico Madrid", "score": 1 } ]
          },
          "content": { "matchFacts": { "events": { "events": [
            { "type": "Half", "time": 45 },
            { "type": "Goal", "time": 43, "overloadTime": null, "isHome": false, "ownGoal": null,
              "player": { "name": "Jorge Domínguez" } },
            { "type": "Card", "time": 50, "isHome": true, "player": { "name": "Rodri" } },
            { "type": "Goal", "time": 57, "overloadTime": null, "isHome": true, "ownGoal": null,
              "player": { "name": "Omar Marmoush" } },
            { "type": "Goal", "time": 59, "overloadTime": null, "isHome": true, "ownGoal": null,
              "player": { "name": "Omar Marmoush" } },
            { "type": "Goal", "time": 90, "overloadTime": null, "isHome": true, "ownGoal": null,
              "player": { "name": "Rayan Aït-Nouri" } }
          ] } } }
        }
    """.trimIndent()

    // ── 선수 교체 (2026-08-20 말라가전 실측 형태) ─────────────────────────
    //
    // 네이버에는 교체 데이터가 아예 없어서 FotMob이 유일한 소스다.

    private val subsResponse = """
        {
          "general": { "homeTeam": { "name": "Atletico Madrid", "id": 9906 },
                       "awayTeam": { "name": "Malaga", "id": 9864 },
                       "started": true, "finished": false },
          "header": { "status": { "started": true, "finished": false, "cancelled": false },
                      "teams": [ { "name": "Atletico Madrid", "score": 2 }, { "name": "Malaga", "score": 0 } ] },
          "content": { "matchFacts": { "events": { "events": [
            { "type": "Substitution", "time": 46, "overloadTime": null, "isHome": true,
              "injuredPlayerOut": false,
              "swap": [ { "name": "Marcos Llorente" }, { "name": "Jorge Domínguez" } ] },
            { "type": "Card", "time": 50, "isHome": true, "player": { "name": "Koke" } },
            { "type": "Substitution", "time": 61, "overloadTime": null, "isHome": false,
              "injuredPlayerOut": true,
              "swap": [ { "name": "David Larrubia" }, { "name": "Chupe" } ] },
            { "type": "Substitution", "time": 70, "overloadTime": null, "isHome": true,
              "swap": [ { "name": "" }, { "name": "Álex Baena" } ] }
          ] } } }
        }
    """.trimIndent()

    @Test
    @DisplayName("교체를 파싱한다 — swap은 [나가는 선수, 들어오는 선수] 순서다")
    fun testParseSubstitutions() {
        val snapshot = client.parseSnapshot(subsResponse)

        // 카드는 섞여 와도 걸러지고, 이름이 빈 항목(70')은 버린다
        assertEquals(2, snapshot.substitutions.size)

        val first = snapshot.substitutions.first()
        assertEquals(46, first.minute)
        assertEquals("Marcos Llorente", first.playerOut)
        assertEquals("Jorge Domínguez", first.playerIn)
        assertTrue(first.isHome)
        assertFalse(first.injury)

        val second = snapshot.substitutions[1]
        assertFalse(second.isHome)
        assertTrue(second.injury, "부상 교체는 injuredPlayerOut으로 구분된다")
    }

    @Test
    @DisplayName("이름이 한쪽이라도 비면 버린다 — 화살표 한쪽이 빈 채로 보드에 뜨면 안 된다")
    fun testSubstitutionNeedsBothNames() {
        val snapshot = client.parseSnapshot(subsResponse)
        assertTrue(snapshot.substitutions.none { it.playerOut.isBlank() || it.playerIn.isBlank() })
    }

    @Test
    @DisplayName("같은 응답은 같은 값을 준다 — 화면이 필드로 중복을 가린다")
    fun testSubstitutionIsStable() {
        // 화면은 이 필드들을 이어붙여 "이미 띄운 교체"를 가린다. 값이 흔들리면
        // 같은 교체가 보드에 두 번 뜬다.
        // (DTO에 계산 프로퍼티를 두지 않는 이유는 LiveMatchState 쪽 주석 참고 —
        //  Jackson이 되읽지 못해 스냅샷 전체가 깨진다)
        val a = client.parseSnapshot(subsResponse).substitutions.first()
        val b = client.parseSnapshot(subsResponse).substitutions.first()
        assertEquals(a, b)
    }

    @Test
    @DisplayName("스코어와 득점자를 파싱한다")
    fun testParseFinishedMatch() {
        val snapshot = client.parseSnapshot(finishedResponse)

        assertEquals(LiveSource.FOTMOB, snapshot.source)
        assertEquals("Manchester City", snapshot.homeTeam)
        assertEquals("Atletico Madrid", snapshot.awayTeam)
        assertEquals(3, snapshot.homeScore)
        assertEquals(1, snapshot.awayScore)

        // 골이 아닌 이벤트(카드·하프타임)가 섞여 와도 골만 추린다
        assertEquals(4, snapshot.goals.size)
        assertEquals(listOf(43, 57, 59, 90), snapshot.goals.map { it.time })

        val atleti = snapshot.goals.first()
        assertEquals("Jorge Domínguez", atleti.name)
        assertFalse(atleti.isHome)
        // 이 필드는 false가 아니라 null로 온다. null을 자책골로 읽으면 안 된다
        assertFalse(atleti.ownGoal)
    }

    @Test
    @DisplayName("상태 문구는 네이버와 같은 한글 어휘로 맞춘다")
    fun testKoreanStatus() {
        // 프론트는 status를 그대로 그린다. 여기서 영문이 나가면 폴백 순간 화면이 튄다
        assertEquals("경기종료", client.parseSnapshot(finishedResponse).status)
        assertTrue(client.parseSnapshot(finishedResponse).finished)

        val before = finishedResponse
            .replace("\"started\": true, \"finished\": true, \"cancelled\": false", "\"started\": false, \"finished\": false, \"cancelled\": false")
        assertEquals("경기전", client.parseSnapshot(before).status)
        assertFalse(client.parseSnapshot(before).finished)

        val live = finishedResponse
            .replace("\"started\": true, \"finished\": true, \"cancelled\": false", "\"started\": true, \"finished\": false, \"cancelled\": false")
        assertEquals("경기중", client.parseSnapshot(live).status)
        assertFalse(client.parseSnapshot(live).finished)

        val cancelled = finishedResponse
            .replace("\"started\": true, \"finished\": true, \"cancelled\": false", "\"started\": false, \"finished\": false, \"cancelled\": true")
        assertEquals("경기취소", client.parseSnapshot(cancelled).status)
        // 취소된 경기도 더 볼 것이 없으므로 폴링을 멈춘다
        assertTrue(client.parseSnapshot(cancelled).finished)
    }

    @Test
    @DisplayName("자책골과 추가시간이 오면 살려서 읽는다")
    fun testOwnGoalAndAddedTime() {
        val snapshot = client.parseSnapshot(
            """
            {
              "general": { "homeTeam": { "name": "Atletico Madrid" }, "awayTeam": { "name": "Malaga" } },
              "header": {
                "status": { "started": true, "finished": false, "cancelled": false },
                "teams": [ { "name": "Atletico Madrid", "score": 1 }, { "name": "Malaga", "score": 1 } ]
              },
              "content": { "matchFacts": { "events": { "events": [
                { "type": "Goal", "time": 45, "overloadTime": 2, "isHome": true, "ownGoal": false,
                  "player": { "name": "Antonio Gil" } },
                { "type": "Goal", "time": 70, "overloadTime": null, "isHome": true, "ownGoal": true,
                  "player": { "name": "Robin Le Normand" } }
              ] } } }
            }
            """.trimIndent()
        )

        val addedTime = snapshot.goals.first()
        assertEquals(45, addedTime.time)
        assertEquals(2, addedTime.addedTime)
        assertEquals("45+2'", addedTime.minute)

        // 자책골을 놓치면 상대 골을 우리 득점으로 표시한다 (설계 8.2)
        assertTrue(snapshot.goals.last().ownGoal)
        assertEquals("⚽ 70' Robin Le Normand (자책골)", LiveMatchPoller.goalText(snapshot.goals.last()))
    }

    @Test
    @DisplayName("경기 전이라 이벤트가 없어도 파싱된다")
    fun testParseNotStarted() {
        val snapshot = client.parseSnapshot(
            """
            {
              "general": { "homeTeam": { "name": "Marseille" }, "awayTeam": { "name": "Atletico Madrid" } },
              "header": {
                "status": { "started": false, "finished": false, "cancelled": false },
                "teams": [ { "name": "Marseille", "score": 0 }, { "name": "Atletico Madrid", "score": 0 } ]
              }
            }
            """.trimIndent()
        )

        assertTrue(snapshot.goals.isEmpty())
        assertEquals("경기전", snapshot.status)
        assertEquals(0, snapshot.homeScore)
    }

    /** 실측 응답의 halfs — 2026-08-09 맨시티전. 킥오프 11:00Z에 표기는 13:03:30 (UTC+2) */
    private fun withHalfs(halfs: String) = """
        {
          "general": { "homeTeam": { "name": "Manchester City" }, "awayTeam": { "name": "Atletico Madrid" } },
          "header": {
            "status": {
              "utcTime": "2026-08-09T11:00:00.000Z",
              "started": true, "finished": true, "cancelled": false,
              "halfs": $halfs
            },
            "teams": [ { "name": "Manchester City", "score": 3 }, { "name": "Atletico Madrid", "score": 1 } ]
          }
        }
    """.trimIndent()

    @Test
    @DisplayName("경기 시계 기준점을 타임존 없이도 읽어낸다")
    fun testParsePeriods() {
        // halfs에는 타임존이 안 적혀 있다. utcTime과 대조해 오프셋을 역산해야 한다 —
        // UTC+2로 못 박으면 FotMob이 표기를 바꾸는 순간 시계가 두 시간 틀어진다
        val periods = client.parseSnapshot(
            withHalfs(
                """{ "firstHalfStarted": "09.08.2026 13:03:30",
                     "firstHalfEnded":    "09.08.2026 13:53:15",
                     "secondHalfStarted": "09.08.2026 14:11:25",
                     "secondHalfEnded":   "09.08.2026 15:03:40" }"""
            )
        ).periods

        // 13:03:30 (UTC+2) = 11:03:30Z — 예정 킥오프보다 3분 30초 늦게 시작
        assertEquals(Instant.parse("2026-08-09T11:03:30Z"), periods?.firstHalfStarted?.toInstant())
        assertEquals(Instant.parse("2026-08-09T11:53:15Z"), periods?.firstHalfEnded?.toInstant())
        assertEquals(Instant.parse("2026-08-09T12:11:25Z"), periods?.secondHalfStarted?.toInstant())
        // 종료 시각 — 화면 시계가 여기서 멈춘다
        assertEquals(Instant.parse("2026-08-09T13:03:40Z"), periods?.secondHalfEnded?.toInstant())
    }

    @Test
    @DisplayName("같은 경기를 다른 타임존으로 적어 와도 같은 순간으로 읽는다")
    fun testParsePeriodsOtherZone() {
        // FotMob이 UTC로 바꿔 적어도 결과가 흔들리면 안 된다
        assertEquals(
            Instant.parse("2026-08-09T11:03:30Z"),
            client.parseSnapshot(withHalfs("""{ "firstHalfStarted": "09.08.2026 11:03:30" }"""))
                .periods?.firstHalfStarted?.toInstant()
        )

        // KST(+9)로 적어 와도 마찬가지 — 리허설 서버(scripts/fake-live-match.js)가 이 표기로 낸다
        assertEquals(
            Instant.parse("2026-08-09T11:03:30Z"),
            client.parseSnapshot(withHalfs("""{ "firstHalfStarted": "09.08.2026 20:03:30" }"""))
                .periods?.firstHalfStarted?.toInstant()
        )
    }

    @Test
    @DisplayName("전반이 아직 안 시작했으면 기준점이 없다")
    fun testPeriodsBeforeKickoff() {
        // 경기 전에는 halfs가 빈 객체로 온다 (실측 확인)
        assertNull(client.parseSnapshot(withHalfs("{}")).periods)
    }

    @Test
    @DisplayName("해석이 말이 안 되면 틀린 시계를 주느니 버린다")
    fun testPeriodsRejectsNonsense() {
        // 예정 킥오프와 몇 시간씩 어긋나면 형식을 잘못 읽고 있다는 뜻이다.
        // 여기서 통과시키면 LED가 엉뚱한 시각을 자신 있게 띄운다
        assertNull(client.parseSnapshot(withHalfs("""{ "firstHalfStarted": "09.08.2026 13:41:00" }""")).periods)
        assertNull(client.parseSnapshot(withHalfs("""{ "firstHalfStarted": "말도 안 되는 값" }""")).periods)
    }

    @Test
    @DisplayName("응답 모양이 바뀌면 조용히 넘어가지 않고 예외를 낸다")
    fun testBrokenResponse() {
        // 이미 한 번 경로가 옮겨진 API다. 0-0으로 조용히 흘러가면 폴백이 있으나 마나다
        assertFailsWith<FotMobScrapeException> { client.parseSnapshot("""{"header":{}}""") }
        assertFailsWith<FotMobScrapeException> { client.parseSnapshot("<html>404</html>") }
    }
}
