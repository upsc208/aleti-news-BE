package com.atleti.news.service

import com.atleti.news.dto.LiveMatchState
import com.atleti.news.dto.LivePeriods
import com.atleti.news.dto.LiveScorer
import com.atleti.news.dto.LiveSubstitution
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 라이브 피드의 판정·표기 로직 (docs/live-match-feed-design.md 5·7·8장).
 * Redis·네이버 호출과 분리된 부분만 여기서 검증한다.
 */
class LiveMatchFeedTest {

    private fun game(
        gameId: String,
        kickoff: String?,
        statusCode: String = "BEFORE",
        statusInfo: String = "경기전"
    ) = NaverGame(
        gameId = gameId,
        kickoff = kickoff?.let { OffsetDateTime.parse(it) },
        categoryName = "라리가",
        stadium = "리야드 에어 메트로폴리타노",
        homeTeamName = "AT 마드리드",
        awayTeamName = "말라가",
        homeScore = 0,
        awayScore = 0,
        statusCode = statusCode,
        statusInfo = statusInfo,
        scorers = emptyList()
    )

    // ── 매핑 (설계 7장) ───────────────────────────────────────────────────

    @Test
    @DisplayName("킥오프가 같은 경기를 짝으로 고른다")
    fun testPairByKickoff() {
        val games = listOf(
            game("other-1", "2026-08-20T02:00:00+09:00"),
            game("Z5BQDTTiMjv6iBO", "2026-08-20T04:00:00+09:00"),
            game("other-2", "2026-08-20T06:30:00+09:00")
        )

        val paired = LiveMatchMapper.pairByKickoff(OffsetDateTime.parse("2026-08-20T04:00:00+09:00"), games)
        assertEquals("Z5BQDTTiMjv6iBO", paired?.gameId)
    }

    @Test
    @DisplayName("킥오프가 30분 안에서 어긋나도 짝을 유지한다")
    fun testPairWithinTolerance() {
        // FotMob과 네이버가 편성 변경을 반영하는 시점이 달라 몇 분 어긋나는 일이 있다
        val games = listOf(game("Z5BQDTTiMjv6iBO", "2026-08-20T04:00:00+09:00"))

        val paired = LiveMatchMapper.pairByKickoff(OffsetDateTime.parse("2026-08-20T04:25:00+09:00"), games)
        assertEquals("Z5BQDTTiMjv6iBO", paired?.gameId)
    }

    @Test
    @DisplayName("30분을 넘게 벌어지면 엉뚱한 경기에 붙이지 않는다")
    fun testPairRejectsFarKickoff() {
        // 잘못 붙으면 다른 경기의 골이 이 방으로 날아간다. 매핑 실패가 오히려 안전하다
        val games = listOf(game("Z5BQDTTiMjv6iBO", "2026-08-20T04:00:00+09:00"))

        assertNull(LiveMatchMapper.pairByKickoff(OffsetDateTime.parse("2026-08-20T06:00:00+09:00"), games))
    }

    @Test
    @DisplayName("킥오프를 못 읽은 경기는 후보에서 빠진다")
    fun testPairSkipsUnparsableKickoff() {
        val games = listOf(
            game("broken", null),
            game("Z5BQDTTiMjv6iBO", "2026-08-20T04:00:00+09:00")
        )

        val paired = LiveMatchMapper.pairByKickoff(OffsetDateTime.parse("2026-08-20T04:05:00+09:00"), games)
        assertEquals("Z5BQDTTiMjv6iBO", paired?.gameId)
    }

    @Test
    @DisplayName("시간대 표기가 달라도 같은 순간이면 짝지어진다")
    fun testPairAcrossOffsets() {
        // 네이버는 KST 벽시계, FotMob 저장분도 KST지만 옛 기록에 UTC가 섞일 수 있다
        val games = listOf(game("Z5BQDTTiMjv6iBO", "2026-08-20T04:00:00+09:00"))

        val paired = LiveMatchMapper.pairByKickoff(OffsetDateTime.parse("2026-08-19T19:00:00Z"), games)
        assertEquals("Z5BQDTTiMjv6iBO", paired?.gameId)
    }

    // ── 종료 판정 (설계 5.4) ──────────────────────────────────────────────

    @Test
    @DisplayName("경기 종료·취소·연기는 폴링을 멈출 상태로 본다")
    fun testFinishedDetection() {
        assertTrue(NaverSportsClient.isFinished(game("g", null, "RESULT", "경기종료")))
        assertTrue(NaverSportsClient.isFinished(game("g", null, "CANCEL", "경기취소")))

        // 실측 전이라 코드를 다 모른다. 한글 표기만으로도 잡혀야 한다 (설계 9.2)
        assertTrue(NaverSportsClient.isFinished(game("g", null, "UNKNOWN_CODE", "경기종료")))
    }

    @Test
    @DisplayName("경기 전·경기 중에는 멈추지 않는다")
    fun testNotFinishedWhileLive() {
        assertFalse(NaverSportsClient.isFinished(game("g", null, "BEFORE", "경기전")))
        assertFalse(NaverSportsClient.isFinished(game("g", null, "STARTED", "경기중")))
        assertFalse(NaverSportsClient.isFinished(game("g", null, "STARTED", "전반")))
    }

    // ── 알림 문구 (설계 6.3 / 6.4 / 8.2) ──────────────────────────────────

    @Test
    @DisplayName("골 알림은 분과 이름으로 적는다")
    fun testGoalText() {
        val scorer = LiveGoal("호르헤 도밍게스", 43, 0, isHome = false, ownGoal = false)
        assertEquals("⚽ 43' 호르헤 도밍게스", LiveMatchPoller.goalText(scorer))

        val addedTime = LiveGoal("안토니오 힐", 45, 2, isHome = true, ownGoal = false)
        assertEquals("⚽ 45+2' 안토니오 힐", LiveMatchPoller.goalText(addedTime))
    }

    @Test
    @DisplayName("자책골은 문구에 표시한다")
    fun testOwnGoalText() {
        // 표시하지 않으면 "르 노르망 골!"로 읽혀 상대 골이 우리 득점으로 둔갑한다 (설계 8.2)
        val ownGoal = LiveGoal("로빈 르 노르망", 70, 0, isHome = true, ownGoal = true)
        assertEquals("⚽ 70' 로빈 르 노르망 (자책골)", LiveMatchPoller.goalText(ownGoal))
    }

    @Test
    @DisplayName("VAR 취소 문구는 어느 골이 취소됐는지 밝힌다")
    fun testCancelText() {
        val scorer = LiveGoal("호르헤 도밍게스", 43, 0, isHome = false, ownGoal = false)
        assertEquals(
            "⚠️ VAR 판독 — 43' 호르헤 도밍게스의 골이 취소되었습니다",
            LiveMatchPoller.cancelText(scorer)
        )
    }

    @Test
    @DisplayName("취소 문구는 사라진 골의 키만으로 복원된다")
    fun testCancelTextFromKeyAlone() {
        // VAR 취소는 응답에서 항목이 사라지는 형태라 득점자 정보가 남아 있지 않다.
        // Redis 집합의 키에서 되살리지 못하면 "누구 골이 취소됐는지" 쓸 수 없다
        val original = LiveGoal("호르헤 도밍게스", 43, 0, isHome = false, ownGoal = false)
        val restored = LiveGoal.fromKey(original.key)

        assertEquals(original, restored)
        assertEquals(LiveMatchPoller.cancelText(original), LiveMatchPoller.cancelText(restored!!))
    }

    @Test
    @DisplayName("깨진 골 키는 null로 흘려보낸다")
    fun testFromKeyRejectsGarbage() {
        // 키 형식을 바꾼 배포 직후 옛 키가 섞여 들어와도 폴러가 죽으면 안 된다
        assertNull(LiveGoal.fromKey("garbage"))
        assertNull(LiveGoal.fromKey("A|43|이름|false"))
        assertNull(LiveGoal.fromKey("A|43+x|이름|false"))
    }

    @Test
    @DisplayName("이름에 구분자가 섞여도 키를 되돌린다")
    fun testFromKeyKeepsNameIntact() {
        val scorer = LiveGoal("데 폴|주니어", 12, 0, isHome = true, ownGoal = false)
        assertEquals(scorer, LiveGoal.fromKey(scorer.key))
    }

    // ── 소스 전환 (설계 8.3) ──────────────────────────────────────────────

    @Test
    @DisplayName("두 소스의 골 키는 이름 표기 때문에 서로 다르다")
    fun testKeysDifferAcrossSources() {
        // 폴백이 위험한 진짜 이유. 그냥 비교하면 이미 알린 골이 전부 새 골로 보인다
        val naver = LiveGoal("호르헤 도밍게스", 43, 0, isHome = false, ownGoal = false)
        val fotmob = LiveGoal("Jorge Domínguez", 43, 0, isHome = false, ownGoal = false)

        assertTrue(naver.key != fotmob.key)
        // 그래서 이름이 아니라 팀·분으로 맞춘다
        assertEquals(naver.totalMinute, fotmob.totalMinute)
        assertEquals(naver.isHome, fotmob.isHome)
    }

    @Test
    @DisplayName("추가시간 골도 절대 분으로 비교된다")
    fun testTotalMinute() {
        // 네이버는 45+2를 (45, 2)로 쪼개 주고 FotMob은 47분 하나로 줄 수 있다
        assertEquals(47, LiveGoal("힐", 45, 2, isHome = true, ownGoal = false).totalMinute)
        assertEquals(47, LiveGoal("Gil", 47, 0, isHome = true, ownGoal = false).totalMinute)
    }

    // ── 라틴 이름 매칭 (LED 보드용) ────────────────────────────────────────

    @Test
    @DisplayName("같은 골을 팀과 분으로 맞춰 라틴 이름을 붙인다")
    fun testPairLatinNames() {
        // 음역표 없이 이름을 얻는 방법 — 같은 경기 같은 분의 골은 같은 골이다
        val korean = listOf(
            LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false),
            LiveGoal("미켈 오야르사발", 24, 0, isHome = false, ownGoal = false)
        )
        val latin = listOf(
            LiveGoal("Julián Álvarez", 12, 0, isHome = true, ownGoal = false),
            LiveGoal("Mikel Oyarzabal", 24, 0, isHome = false, ownGoal = false)
        )

        val paired = LiveMatchPoller.pairLatinNames(korean, latin)
        assertEquals("Julián Álvarez", paired[korean[0].key])
        assertEquals("Mikel Oyarzabal", paired[korean[1].key])
    }

    @Test
    @DisplayName("제공사끼리 분이 어긋나도 붙는다")
    fun testPairLatinNamesWithMinuteDrift() {
        // 네이버 45+2 = 47분, FotMob은 46분으로 적는 식으로 흔히 어긋난다
        val korean = listOf(LiveGoal("안토니오 힐", 45, 2, isHome = true, ownGoal = false))
        val latin = listOf(LiveGoal("Antonio Gil", 46, 0, isHome = true, ownGoal = false))

        assertEquals("Antonio Gil", LiveMatchPoller.pairLatinNames(korean, latin)[korean[0].key])
    }

    @Test
    @DisplayName("같은 팀이 연달아 넣어도 이름이 뭉개지지 않는다")
    fun testPairLatinNamesConsumesEachMatchOnce() {
        val korean = listOf(
            LiveGoal("훌리안 알바레스", 58, 0, isHome = true, ownGoal = false),
            LiveGoal("코케", 59, 0, isHome = true, ownGoal = false)
        )
        val latin = listOf(
            LiveGoal("Julián Álvarez", 58, 0, isHome = true, ownGoal = false),
            LiveGoal("Koke", 59, 0, isHome = true, ownGoal = false)
        )

        val paired = LiveMatchPoller.pairLatinNames(korean, latin)
        assertEquals("Julián Álvarez", paired[korean[0].key])
        assertEquals("Koke", paired[korean[1].key])
    }

    @Test
    @DisplayName("짝이 없으면 엉뚱한 이름을 붙이는 대신 비워 둔다")
    fun testPairLatinNamesLeavesUnmatchedEmpty() {
        val korean = listOf(
            LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false),
            LiveGoal("코케", 70, 0, isHome = true, ownGoal = false)
        )
        // FotMob이 아직 70분 골을 반영하지 않은 상태
        val latin = listOf(LiveGoal("Julián Álvarez", 12, 0, isHome = true, ownGoal = false))

        val paired = LiveMatchPoller.pairLatinNames(korean, latin)
        assertEquals(1, paired.size)
        assertNull(paired[korean[1].key])
    }

    @Test
    @DisplayName("팀이 다르면 분이 같아도 붙이지 않는다")
    fun testPairLatinNamesRespectsSide() {
        // 양 팀이 같은 분에 넣는 일은 없지만, 붙였다간 상대 선수 이름이 우리 골에 뜬다
        val korean = listOf(LiveGoal("훌리안 알바레스", 30, 0, isHome = true, ownGoal = false))
        val latin = listOf(LiveGoal("Mikel Oyarzabal", 30, 0, isHome = false, ownGoal = false))

        assertEquals(emptyMap(), LiveMatchPoller.pairLatinNames(korean, latin))
    }

    @Test
    @DisplayName("라틴 이름은 골 키를 바꾸지 않는다")
    fun testLatinNameDoesNotAffectKey() {
        // 키가 바뀌면 이름이 뒤늦게 채워지는 순간 이미 알린 골을 다시 알린다
        val plain = LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false)
        assertEquals(plain.key, plain.copy(nameLatin = "Julián Álvarez").key)
    }

    // ── 종료·하프타임 판정 (8/20 말라가전에서 드러난 문제) ────────────────
    //
    // 네이버의 하프타임 표기가 `전반종료`인데 contains("종료")가 이걸 경기 종료로 읽었다.
    // 폴러가 전반 끝나자마자 멈춰서 후반 골이 통째로 안 나갔다 (docs/round1.md).

    @Test
    @DisplayName("전반종료는 경기 종료가 아니다")
    fun testHalftimeIsNotFinished() {
        val g = game("x", null, statusCode = "STARTED", statusInfo = "전반종료")
        assertFalse(NaverSportsClient.isFinished(g))
        assertTrue(NaverSportsClient.isHalftime(g))
    }

    @Test
    @DisplayName("경기종료는 경기 종료다")
    fun testFullTimeIsFinished() {
        assertTrue(NaverSportsClient.isFinished(game("x", null, statusCode = "RESULT", statusInfo = "경기종료")))
        // 표기만 있고 코드가 아직 안 바뀐 경우도 잡아야 한다
        assertTrue(NaverSportsClient.isFinished(game("x", null, statusCode = "STARTED", statusInfo = "경기종료")))
    }

    @Test
    @DisplayName("statusCode가 종료면 표기와 무관하게 종료다")
    fun testFinishedByCode() {
        assertTrue(NaverSportsClient.isFinished(game("x", null, statusCode = "RESULT", statusInfo = "경기중")))
        assertTrue(NaverSportsClient.isFinished(game("x", null, statusCode = "CANCEL", statusInfo = "경기전")))
    }

    @Test
    @DisplayName("경기중·경기전은 종료도 하프타임도 아니다")
    fun testInProgressIsNeither() {
        for (label in listOf("경기중", "경기전")) {
            val g = game("x", null, statusCode = "STARTED", statusInfo = label)
            assertFalse(NaverSportsClient.isFinished(g), label)
            assertFalse(NaverSportsClient.isHalftime(g), label)
        }
    }

    @Test
    @DisplayName("모르는 표기는 안 끝난 것으로 본다 — 일찍 멈추면 경기가 통째로 날아간다")
    fun testUnknownLabelKeepsPolling() {
        // 늦게 멈추는 쪽은 킥오프 +3시간에 어차피 폴링이 끝난다. 일찍 멈추는 쪽은 복구가 없다
        assertFalse(NaverSportsClient.isFinished(game("x", null, statusCode = "STARTED", statusInfo = "후반종료")))
        assertFalse(NaverSportsClient.isFinished(game("x", null, statusCode = "STARTED", statusInfo = "연장전반")))
    }

    @Test
    @DisplayName("공백이 섞여도 정확 일치로 본다")
    fun testLabelIgnoresSpaces() {
        assertTrue(NaverSportsClient.isFinished(game("x", null, statusCode = "STARTED", statusInfo = "경기 종료")))
        assertTrue(NaverSportsClient.isHalftime(game("x", null, statusCode = "STARTED", statusInfo = "전반 종료")))
    }

    // ── 상태 변화 브로드캐스트 (8/15 실경기에서 드러난 문제) ──────────────
    //
    // 8/15 마르세유전에서 하프타임에 화면 시계가 안 멈췄다. 서버는 전반 종료 시각을
    // 이미 받아 스냅샷에 넣어 뒀는데, 그걸 프론트에 실어 보내는 통로가 **골 알림뿐**이라
    // 골이 없는 하프타임에는 아무것도 안 나갔다.

    private fun state(
        status: String = "경기중",
        periods: LivePeriods? = null,
        homeScore: Int = 0,
        awayScore: Int = 0
    ) = LiveMatchState(
        status = status,
        homeTeam = "AT 마드리드",
        awayTeam = "마르세유",
        homeScore = homeScore,
        awayScore = awayScore,
        scorers = emptyList(),
        kickoff = OffsetDateTime.parse("2026-08-15T00:30:00+09:00"),
        periods = periods,
        updatedAt = OffsetDateTime.parse("2026-08-15T01:20:00+09:00")
    )

    private fun at(iso: String) = OffsetDateTime.parse(iso)

    @Test
    @DisplayName("하프타임 진입 — 골이 없어도 상태 변화로 잡힌다")
    fun testHalftimeIsAClockChange() {
        val firstHalf = at("2026-08-15T00:31:35+09:00")
        val playing = state(periods = LivePeriods(firstHalfStarted = firstHalf))
        val halftime = state(
            periods = LivePeriods(
                firstHalfStarted = firstHalf,
                firstHalfEnded = at("2026-08-15T01:18:20+09:00")
            )
        )

        assertTrue(LiveMatchPoller.clockChanged(playing, halftime))
    }

    @Test
    @DisplayName("시계 기준점이 처음 잡히는 순간도 상태 변화다")
    fun testClockAnchorArrivalIsAClockChange() {
        // 이게 안 잡히면 화면은 예정 킥오프로 계속 센다 — 8/15에 시계가 90초 앞섰던 원인
        val before = state(periods = null)
        val anchored = state(periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00")))

        assertTrue(LiveMatchPoller.clockChanged(before, anchored))
    }

    @Test
    @DisplayName("후반 시작과 경기 종료도 각각 잡힌다")
    fun testSecondHalfAndFinishAreClockChanges() {
        val periods = LivePeriods(
            firstHalfStarted = at("2026-08-15T00:31:35+09:00"),
            firstHalfEnded = at("2026-08-15T01:18:20+09:00")
        )
        val halftime = state(periods = periods)
        val secondHalf = state(periods = periods.copy(secondHalfStarted = at("2026-08-15T01:33:40+09:00")))

        assertTrue(LiveMatchPoller.clockChanged(halftime, secondHalf))
        assertTrue(LiveMatchPoller.clockChanged(secondHalf, state("경기종료", secondHalf.periods)))
    }

    @Test
    @DisplayName("아무것도 안 바뀐 턴에는 발행하지 않는다")
    fun testQuietTurnPublishesNothing() {
        // 폴러는 5초마다 도는데 매 턴 브로드캐스트하면 조용한 45분 동안 540번이 나간다
        val periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00"))
        assertFalse(LiveMatchPoller.clockChanged(state(periods = periods), state(periods = periods)))
    }

    @Test
    @DisplayName("Redis를 왕복해도 조용한 턴은 조용하다")
    fun testQuietTurnSurvivesRedisRoundTrip() {
        // 폴러가 비교하는 「직전 상태」는 Redis에 넣었다 꺼낸 값이다. Jackson이 역직렬화하며
        // 오프셋을 UTC로 옮기므로 `+09:00`과 `Z`가 다른 값으로 잡힌다 — 그대로 두면
        // 조용한 45분 내내 5초마다 상태 변화가 발행된다.
        val mapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val fresh = state(
            periods = LivePeriods(
                firstHalfStarted = at("2026-08-15T00:31:35+09:00"),
                firstHalfEnded = at("2026-08-15T01:18:20+09:00")
            )
        )
        val fromRedis = mapper.readValue(mapper.writeValueAsString(fresh), LiveMatchState::class.java)

        assertFalse(LiveMatchPoller.clockChanged(fromRedis, fresh))
    }

    @Test
    @DisplayName("Redis를 왕복해도 진짜 변화는 잡아낸다")
    fun testRealChangeSurvivesRedisRoundTrip() {
        val mapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val firstHalf = at("2026-08-15T00:31:35+09:00")
        val playing = state(periods = LivePeriods(firstHalfStarted = firstHalf))
        val fromRedis = mapper.readValue(mapper.writeValueAsString(playing), LiveMatchState::class.java)
        val halftime = state(
            periods = LivePeriods(firstHalfStarted = firstHalf, firstHalfEnded = at("2026-08-15T01:18:20+09:00"))
        )

        assertTrue(LiveMatchPoller.clockChanged(fromRedis, halftime))
    }

    @Test
    @DisplayName("점수 변화만으로는 발행하지 않는다 — 골 알림이 이미 나른다")
    fun testScoreChangeAloneIsNotAClockChange() {
        val periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00"))
        val before = state(periods = periods, homeScore = 0)
        val after = state(periods = periods, homeScore = 1)

        assertFalse(LiveMatchPoller.clockChanged(before, after))
    }

    // ── Redis 왕복 (스냅샷은 매 턴 JSON으로 저장했다 다시 읽는다) ──────────

    @Test
    @DisplayName("스냅샷 전체가 JSON을 왕복해도 그대로다")
    fun testStateSurvivesJsonRoundTrip() {
        /*
          이 테스트가 막는 것: DTO에 계산 프로퍼티(`val x get() = ...`)를 하나라도 두면
          Jackson이 내보내기만 하고 되읽지 못해 **스냅샷 전체가 역직렬화에 실패한다.**
          그러면 `live`가 통째로 null이 되고, 직전 상태를 못 읽으니 골 알림까지 같이 죽는다.
          LiveSubstitution에 key를 뒀다가 실제로 그렇게 됐다.
        */
        val mapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val full = state(
            periods = LivePeriods(
                firstHalfStarted = at("2026-08-20T04:08:20+09:00"),
                firstHalfEnded = at("2026-08-20T04:55:10+09:00")
            ),
            homeScore = 2,
            awayScore = 1
        ).copy(
            scorers = listOf(
                LiveScorer("훌리안 알바레스", 14, 0, isHome = true, ownGoal = false, nameLatin = "Julián Álvarez")
            ),
            substitutions = listOf(
                LiveSubstitution(46, 0, isHome = true, playerOut = "Marcos Llorente", playerIn = "Jorge Domínguez"),
                LiveSubstitution(70, 2, isHome = false, playerOut = "David Larrubia", playerIn = "Chupe", injury = true)
            )
        )

        val back = mapper.readValue(mapper.writeValueAsString(full), LiveMatchState::class.java)

        assertEquals(full.substitutions, back.substitutions)
        assertEquals(full.scorers, back.scorers)
        assertEquals(full.homeScore, back.homeScore)
        assertEquals(full.status, back.status)
    }

    // ── 라이브 정보 지연 (8/20 실측: 골 알림이 시청자보다 7~31초 빨랐다) ──

    private fun snap(home: Int, away: Int) = LiveSnapshot(
        source = LiveSource.NAVER, status = "경기중",
        homeTeam = "AT 마드리드", awayTeam = "말라가",
        homeScore = home, awayScore = away, competition = "라리가",
        goals = emptyList(), finished = false
    )

    @Test
    @DisplayName("아직 안 묵은 스냅샷은 안 내보낸다")
    fun testDrainHoldsFreshSnapshot() {
        val t0 = java.time.Instant.parse("2026-08-20T04:30:00Z")
        val q = ArrayDeque(listOf(t0 to snap(1, 0)))

        // 20초밖에 안 지났다 (지연 30초)
        assertNull(LiveMatchPoller.drainDue(q, t0.plusSeconds(20).minusSeconds(30)))
        assertEquals(1, q.size, "대기열에 그대로 남아 있어야 한다")
    }

    @Test
    @DisplayName("30초가 지나면 내보낸다")
    fun testDrainReleasesAfterDelay() {
        val t0 = java.time.Instant.parse("2026-08-20T04:30:00Z")
        val q = ArrayDeque(listOf(t0 to snap(1, 0)))

        val due = LiveMatchPoller.drainDue(q, t0.plusSeconds(30).minusSeconds(30))
        assertEquals(1, due?.homeScore)
        assertTrue(q.isEmpty())
    }

    @Test
    @DisplayName("묵은 게 여럿이면 그중 가장 최신을 내보낸다")
    fun testDrainTakesNewestDue() {
        // 스냅샷은 매번 전체 상태라 중간을 건너뛰어도 변화를 놓치지 않는다
        val t0 = java.time.Instant.parse("2026-08-20T04:30:00Z")
        val q = ArrayDeque(
            listOf(
                t0 to snap(0, 0),
                t0.plusSeconds(5) to snap(1, 0),
                t0.plusSeconds(10) to snap(2, 0),
                t0.plusSeconds(60) to snap(3, 0)   // 아직 안 묵음
            )
        )

        val due = LiveMatchPoller.drainDue(q, t0.plusSeconds(10))
        assertEquals(2, due?.homeScore, "10초까지 중 가장 최신")
        assertEquals(1, q.size, "안 묵은 것만 남는다")
    }

    // ── 화면 시계 지연 (8/20 말라가전 실측: 쿠팡플레이보다 27~33초 빨랐다) ──

    @Test
    @DisplayName("기준점 셋을 같은 폭으로 민다")
    fun testClockDelayShiftsAllAnchors() {
        val p = LivePeriods(
            firstHalfStarted = at("2026-08-20T04:08:20+09:00"),
            firstHalfEnded = at("2026-08-20T04:55:10+09:00"),
            secondHalfStarted = at("2026-08-20T05:10:40+09:00")
        )
        val d = LiveMatchPoller.delayed(p, 30)!!

        assertEquals(at("2026-08-20T04:08:50+09:00"), d.firstHalfStarted)
        assertEquals(at("2026-08-20T04:55:40+09:00"), d.firstHalfEnded)
        assertEquals(at("2026-08-20T05:11:10+09:00"), d.secondHalfStarted)
    }

    @Test
    @DisplayName("하프타임에 멈추는 값은 지연과 무관하다 — 차이라서")
    fun testClockDelayKeepsFrozenValue() {
        // 화면은 하프타임에 `firstHalfEnded - firstHalfStarted`를 띄운다.
        // 둘을 같이 밀었으므로 멈춘 시각은 그대로여야 한다
        val p = LivePeriods(
            firstHalfStarted = at("2026-08-20T04:08:20+09:00"),
            firstHalfEnded = at("2026-08-20T04:55:10+09:00")
        )
        val d = LiveMatchPoller.delayed(p, 30)!!

        val before = java.time.Duration.between(p.firstHalfStarted, p.firstHalfEnded)
        val after = java.time.Duration.between(d.firstHalfStarted, d.firstHalfEnded)
        assertEquals(before, after)
    }

    @Test
    @DisplayName("0이면 아무것도 안 민다 — 지연 없이 되돌릴 수 있어야 한다")
    fun testClockDelayZeroIsNoop() {
        val p = LivePeriods(firstHalfStarted = at("2026-08-20T04:08:20+09:00"))
        assertEquals(p, LiveMatchPoller.delayed(p, 0))
    }

    @Test
    @DisplayName("기준점이 없으면 그대로 null")
    fun testClockDelayNullSafe() {
        assertNull(LiveMatchPoller.delayed(null, 30))
    }

    // ── 시계 조회 간격 ────────────────────────────────────────────────────

    @Test
    @DisplayName("기준점이 없으면 촘촘히 물어본다")
    fun testClockRetryIsUrgentWithoutAnchor() {
        val now = at("2026-08-15T00:31:00+09:00").toInstant()
        assertEquals(Duration.ofSeconds(15), LiveMatchPoller.clockRetryInterval(null, now))
    }

    @Test
    @DisplayName("전반 한복판에는 느슨하게 물어본다")
    fun testClockRetryIsRelaxedMidFirstHalf() {
        val periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00"))
        val now = at("2026-08-15T01:00:00+09:00").toInstant()   // 28분 경과
        assertEquals(Duration.ofSeconds(60), LiveMatchPoller.clockRetryInterval(periods, now))
    }

    @Test
    @DisplayName("전반 43분부터는 하프타임을 지켜본다")
    fun testClockRetryTightensBeforeHalftime() {
        val periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00"))
        val now = at("2026-08-15T01:15:00+09:00").toInstant()   // 43분 경과
        assertEquals(Duration.ofSeconds(15), LiveMatchPoller.clockRetryInterval(periods, now))
    }

    @Test
    @DisplayName("하프타임 동안에는 후반 시작을 촘촘히 기다린다")
    fun testClockRetryIsUrgentDuringHalftime() {
        val periods = LivePeriods(
            firstHalfStarted = at("2026-08-15T00:31:35+09:00"),
            firstHalfEnded = at("2026-08-15T01:18:20+09:00")
        )
        val now = at("2026-08-15T01:25:00+09:00").toInstant()
        assertEquals(Duration.ofSeconds(15), LiveMatchPoller.clockRetryInterval(periods, now))
    }
}
