package com.atleti.news.live.domain

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
import com.atleti.news.live.external.NaverGame
import com.atleti.news.live.external.NaverSportsClient

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

    // ── 후반 판정 (8/30 세비야전) ─────────────────────────────────────────
    //
    // 그 경기 로그에서 확인한 라리가 표기: `전반 3'` → `전반종료` → `후반 1'` → `경기종료`.
    // 설계 문서가 예로 들던 `경기중`은 한 번도 안 나왔다.

    @Test
    @DisplayName("후반 표기는 분이 붙어 있어도 후반으로 본다")
    fun testSecondHalfLabels() {
        for (label in listOf("후반 1'", "후반 45'", "경기중")) {
            assertTrue(
                NaverSportsClient.isSecondHalf(game("x", null, statusCode = "STARTED", statusInfo = label)),
                label
            )
        }
    }

    @Test
    @DisplayName("전반·하프타임·종료는 후반이 아니다")
    fun testSecondHalfExcludesEverythingElse() {
        // `전반 50'`이 핵심이다 — 이걸 후반으로 읽어 하프타임 12초 만에 기준점이 찍혔다
        for (label in listOf("전반 50'", "전반종료", "경기종료", "경기전", "후반종료")) {
            assertFalse(
                NaverSportsClient.isSecondHalf(game("x", null, statusCode = "STARTED", statusInfo = label)),
                label
            )
        }
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
        val q = ArrayDeque(listOf(LiveMatchPoller.Buffered(t0, snap(1, 0))))

        // 20초밖에 안 지났다 (지연 30초)
        assertNull(LiveMatchPoller.drainDue(q, t0.plusSeconds(20).minusSeconds(30)))
        assertEquals(1, q.size, "대기열에 그대로 남아 있어야 한다")
    }

    @Test
    @DisplayName("30초가 지나면 내보낸다")
    fun testDrainReleasesAfterDelay() {
        val t0 = java.time.Instant.parse("2026-08-20T04:30:00Z")
        val q = ArrayDeque(listOf(LiveMatchPoller.Buffered(t0, snap(1, 0))))

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
                LiveMatchPoller.Buffered(t0, snap(0, 0)),
                LiveMatchPoller.Buffered(t0.plusSeconds(5), snap(1, 0)),
                LiveMatchPoller.Buffered(t0.plusSeconds(10), snap(2, 0)),
                LiveMatchPoller.Buffered(t0.plusSeconds(60), snap(3, 0))   // 아직 안 묵음
            )
        )

        val due = LiveMatchPoller.drainDue(q, t0.plusSeconds(10))
        assertEquals(2, due?.homeScore, "10초까지 중 가장 최신")
        assertEquals(1, q.size, "안 묵은 것만 남는다")
    }

    // ── 이미 늦게 온 골은 더 붙잡지 않는다 (8/23 비야레알전) ──

    /**
     * 그 경기의 실제 기준점 — FotMob `halfs`에서 그대로 가져왔다.
     *
     * **로그에 찍힌 값을 쓰면 안 된다.** 상태 변화 로그의 `periods`는 이미 [LiveMatchPoller.delayed]를
     * 통과한 값이라 지연만큼 더해져 있다. 여기 필요한 건 실제 시각이다.
     */
    private val villarreal = LivePeriods(
        firstHalfStarted = at("2026-08-23T17:02:02+02:00"),
        firstHalfEnded = at("2026-08-23T17:52:43+02:00"),
        secondHalfStarted = at("2026-08-23T18:10:36+02:00")
    )

    private fun goal(time: Int, addedTime: Int = 0) =
        LiveGoal("시메오네", time, addedTime, isHome = true, ownGoal = false)

    @Test
    @DisplayName("네이버가 늦게 준 골은 기준이 실제 시각으로 당겨진다")
    fun testReleaseAnchorRewindsForLateGoal() {
        // 실제 85' 골 = 후반 40분 = 18:50:36+02:00. 네이버는 18:51:27에야 실어 줬다
        val arrival = at("2026-08-23T18:51:27+02:00").toInstant()

        val anchor = LiveMatchPoller.releaseAnchor(arrival, listOf(goal(85)), villarreal)

        assertEquals(at("2026-08-23T18:50:36+02:00").toInstant(), anchor)
        // 지연 25초를 재도 이미 지난 시각이라 이번 턴에 바로 나간다
        assertTrue(anchor.plusSeconds(25) < arrival, "51초 늦게 온 골은 더 붙잡을 이유가 없다")
    }

    @Test
    @DisplayName("추정이 도착보다 늦게 잡히면 도착 시각으로 자른다")
    fun testReleaseAnchorNeverExceedsArrival() {
        // 78' 골이 그랬다 — 분 표기가 1분 단위라 "그 분의 끝"(18:43:36)이
        // 실제 도착(18:43:22)보다 뒤다. 여기서 미래를 기준으로 두면 지금보다 더 붙잡게 된다
        val arrival = at("2026-08-23T18:43:22+02:00").toInstant()

        assertEquals(arrival, LiveMatchPoller.releaseAnchor(arrival, listOf(goal(78)), villarreal))
    }

    @Test
    @DisplayName("새 골이 없으면 지금까지처럼 도착 시각부터 잰다")
    fun testReleaseAnchorKeepsArrivalWithoutGoals() {
        // 시계 틱·교체는 소스가 실시간으로 준다. 여기까지 당기면 지연이 통째로 무너진다
        val arrival = at("2026-08-23T18:30:00+02:00").toInstant()

        assertEquals(arrival, LiveMatchPoller.releaseAnchor(arrival, emptyList(), villarreal))
    }

    @Test
    @DisplayName("기준점이 없으면 도착 시각으로 물러선다")
    fun testReleaseAnchorFallsBackWithoutPeriods() {
        // 시각을 지어내느니 지금까지의 동작을 지킨다
        val arrival = at("2026-08-23T18:51:27+02:00").toInstant()

        assertEquals(arrival, LiveMatchPoller.releaseAnchor(arrival, listOf(goal(85)), null))
        assertEquals(
            arrival,
            LiveMatchPoller.releaseAnchor(arrival, listOf(goal(85)), LivePeriods(firstHalfStarted = null))
        )
    }

    @Test
    @DisplayName("한 스냅샷에 두 골이면 늦은 쪽을 기준으로 잡는다")
    fun testReleaseAnchorTakesLatestGoal() {
        // 이른 쪽으로 잡으면 아직 안 익은 골이 같이 앞질러 나간다
        val arrival = at("2026-08-23T18:52:00+02:00").toInstant()

        val anchor = LiveMatchPoller.releaseAnchor(arrival, listOf(goal(59), goal(85)), villarreal)
        assertEquals(at("2026-08-23T18:50:36+02:00").toInstant(), anchor)
    }

    @Test
    @DisplayName("전반 추가시간 골을 후반으로 읽지 않는다")
    fun testOccurredAtKeepsAddedTimeInFirstHalf() {
        // totalMinute(47)으로 전·후반을 가르면 45+2가 후반 2분이 되어 20분쯤 앞으로 튄다
        assertEquals(
            at("2026-08-23T17:49:02+02:00").toInstant(),   // 전반 시작 + 47분
            LiveMatchPoller.occurredAt(goal(45, 2), villarreal)
        )
    }

    @Test
    @DisplayName("후반 추가시간 골도 후반 시작부터 센다")
    fun testOccurredAtHandlesSecondHalfAddedTime() {
        assertEquals(
            at("2026-08-23T18:58:36+02:00").toInstant(),   // 후반 시작 + 48분
            LiveMatchPoller.occurredAt(goal(90, 3), villarreal)
        )
    }

    @Test
    @DisplayName("늦게 온 골은 앞에 쌓인 시계 틱에 막히지 않는다")
    fun testDrainLetsLateGoalPassQueuedTicks() {
        // 대기열은 도착 순인데 기준 시각은 그렇지 않다. 앞에서부터 훑다 멈추면
        // 기준을 당겨 놓고도 그 골이 다시 25초를 갇힌다 — 당긴 의미가 사라진다
        val t0 = java.time.Instant.parse("2026-08-23T16:51:00Z")
        val q = ArrayDeque(
            listOf(
                LiveMatchPoller.Buffered(t0.plusSeconds(5), snap(0, 0)),    // 시계 틱, 아직 안 익음
                LiveMatchPoller.Buffered(t0.plusSeconds(10), snap(0, 0)),   // 시계 틱, 아직 안 익음
                LiveMatchPoller.Buffered(t0.minusSeconds(51), snap(1, 0))   // 늦게 온 골 — 이미 익음
            )
        )

        val due = LiveMatchPoller.drainDue(q, t0)
        assertEquals(1, due?.homeScore, "익은 골이 나가야 한다")
        assertTrue(q.isEmpty(), "앞의 틱들은 버린다 — 스냅샷은 전체 상태라 잃는 것이 없다")
    }

    @Test
    @DisplayName("되돌아가지 않는다 — 항상 더 최신 쪽을 내보낸다")
    fun testDrainNeverGoesBackwards() {
        // 골보다 VAR 취소가 먼저 나가는 순서 뒤집힘을 막는 성질
        val t0 = java.time.Instant.parse("2026-08-23T16:51:00Z")
        val q = ArrayDeque(
            listOf(
                LiveMatchPoller.Buffered(t0.minusSeconds(60), snap(1, 0)),   // 골
                LiveMatchPoller.Buffered(t0.minusSeconds(10), snap(0, 0))    // 취소, 더 최신
            )
        )

        assertEquals(0, LiveMatchPoller.drainDue(q, t0)?.homeScore, "더 최신인 취소 상태가 나간다")
        assertTrue(q.isEmpty())
    }

    // ── 화면 시계 지연 (8/20 말라가전 실측: 쿠팡플레이보다 27~33초 빨랐다) ──

    @Test
    @DisplayName("기준점 넷을 같은 폭으로 민다")
    fun testClockDelayShiftsAllAnchors() {
        val p = LivePeriods(
            firstHalfStarted = at("2026-08-20T04:08:20+09:00"),
            firstHalfEnded = at("2026-08-20T04:55:10+09:00"),
            secondHalfStarted = at("2026-08-20T05:10:40+09:00"),
            secondHalfEnded = at("2026-08-20T06:02:15+09:00")
        )
        val d = LiveMatchPoller.delayed(p, 30)!!

        assertEquals(at("2026-08-20T04:08:50+09:00"), d.firstHalfStarted)
        assertEquals(at("2026-08-20T04:55:40+09:00"), d.firstHalfEnded)
        assertEquals(at("2026-08-20T05:11:10+09:00"), d.secondHalfStarted)
        // 하나라도 빠뜨리면 종료 시각만 안 밀려 시계가 그 폭만큼 더 가다 멈춘다
        assertEquals(at("2026-08-20T06:02:45+09:00"), d.secondHalfEnded)
    }

    @Test
    @DisplayName("종료 시각도 차이로 쓰이므로 멈춘 값은 지연과 무관하다")
    fun testFullTimeFrozenValueSurvivesDelay() {
        // 화면은 종료 뒤 `45:00 + (secondHalfEnded − secondHalfStarted)`에 멈춘다
        val p = LivePeriods(
            secondHalfStarted = at("2026-08-20T05:10:40+09:00"),
            secondHalfEnded = at("2026-08-20T06:02:15+09:00")
        )
        val d = LiveMatchPoller.delayed(p, 25)!!

        assertEquals(
            java.time.Duration.between(p.secondHalfStarted, p.secondHalfEnded),
            java.time.Duration.between(d.secondHalfStarted, d.secondHalfEnded)
        )
    }

    @Test
    @DisplayName("종료 시각이 들어오면 상태 변화로 잡힌다 — 안 그러면 화면이 모른다")
    fun testFullTimeIsAClockChange() {
        val running = LivePeriods(
            firstHalfStarted = at("2026-08-20T04:08:20+09:00"),
            secondHalfStarted = at("2026-08-20T05:10:40+09:00")
        )
        val stopped = running.copy(secondHalfEnded = at("2026-08-20T06:02:15+09:00"))

        assertTrue(
            LiveMatchPoller.clockChanged(state(periods = running), state(periods = stopped))
        )
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

    /** 예정 킥오프 — 기준점이 없는 구간의 간격은 이 시각을 기준으로 갈린다 */
    private val kickoff = at("2026-08-15T00:30:00+09:00").toInstant()

    @Test
    @DisplayName("킥오프 직후 기준점이 없으면 턴마다 물어본다 — 보드가 그동안 꺼져 있다")
    fun testClockRetryIsImmediateRightAfterKickoff() {
        val now = at("2026-08-15T00:31:00+09:00").toInstant()   // 예정 킥오프 +1분
        assertEquals(Duration.ofSeconds(5), LiveMatchPoller.clockRetryInterval(null, now, kickoff))
    }

    @Test
    @DisplayName("세레머니로 킥오프가 8분 늦어도 아직 촘촘히 본다")
    fun testClockRetryStaysImmediateThroughCeremony() {
        // 8/20 말라가전 실측: 예정 04:00 → 실제 04:08:20
        val now = at("2026-08-15T00:38:20+09:00").toInstant()
        assertEquals(Duration.ofSeconds(5), LiveMatchPoller.clockRetryInterval(null, now, kickoff))
    }

    @Test
    @DisplayName("10분이 지나도록 기준점이 안 오면 물러선다 — 243KB를 세 시간 때리지 않는다")
    fun testClockRetryBacksOffWhenAnchorNeverArrives() {
        val now = at("2026-08-15T00:41:00+09:00").toInstant()   // 예정 킥오프 +11분
        assertEquals(Duration.ofSeconds(15), LiveMatchPoller.clockRetryInterval(null, now, kickoff))
    }

    @Test
    @DisplayName("전반 한복판에는 느슨하게 물어본다")
    fun testClockRetryIsRelaxedMidFirstHalf() {
        val periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00"))
        val now = at("2026-08-15T01:00:00+09:00").toInstant()   // 28분 경과
        assertEquals(Duration.ofSeconds(60), LiveMatchPoller.clockRetryInterval(periods, now, kickoff))
    }

    @Test
    @DisplayName("전반 43분부터는 하프타임을 지켜본다")
    fun testClockRetryTightensBeforeHalftime() {
        val periods = LivePeriods(firstHalfStarted = at("2026-08-15T00:31:35+09:00"))
        val now = at("2026-08-15T01:15:00+09:00").toInstant()   // 43분 경과
        assertEquals(Duration.ofSeconds(15), LiveMatchPoller.clockRetryInterval(periods, now, kickoff))
    }

    @Test
    @DisplayName("하프타임 동안에는 후반 시작을 촘촘히 기다린다")
    fun testClockRetryIsUrgentDuringHalftime() {
        val periods = LivePeriods(
            firstHalfStarted = at("2026-08-15T00:31:35+09:00"),
            firstHalfEnded = at("2026-08-15T01:18:20+09:00")
        )
        val now = at("2026-08-15T01:25:00+09:00").toInstant()
        assertEquals(Duration.ofSeconds(15), LiveMatchPoller.clockRetryInterval(periods, now, kickoff))
    }

    // ── 후반 시작 판정 (8/30 세비야전 재현) ──────────────────────────────
    //
    // 그 경기의 실제 시각. 로그의 periods는 delayed()를 통과한 값이라 25초를 뺐다.
    //   전반 시작 19:30:43Z · 전반 종료 20:21:51Z(FotMob) · 후반 시작 20:38:34Z

    private val firstHalfEnded = at("2026-08-29T20:21:51Z")

    private fun naverSnap(label: String) =
        game("x", null, statusCode = "STARTED", statusInfo = label).toSnapshot()

    @Test
    @DisplayName("전반 종료 직후 네이버가 아직 `전반 50'`이면 후반으로 보지 않는다")
    fun testSecondHalfNotStampedWhileSourceStillSaysFirstHalf() {
        // 실제로 여기서 찍혔다 — 전반 종료 12초 뒤. 시계가 45:00부터 다시 돌기 시작했다
        val now = firstHalfEnded.plusSeconds(12)

        assertFalse(LiveMatchPoller.secondHalfBegun(naverSnap("전반 50'"), firstHalfEnded, now))
    }

    @Test
    @DisplayName("하프타임 표기 동안에도 찍지 않는다")
    fun testSecondHalfNotStampedDuringHalftime() {
        val now = firstHalfEnded.plusMinutes(8)

        assertFalse(LiveMatchPoller.secondHalfBegun(naverSnap("전반종료"), firstHalfEnded, now))
    }

    @Test
    @DisplayName("네이버가 후반이라고 말하면 그때 찍는다")
    fun testSecondHalfStampedOnPositiveSignal() {
        // 실제 후반 시작 20:38:34Z — 전반 종료로부터 16분 43초
        val now = firstHalfEnded.plusMinutes(16).plusSeconds(43)

        assertTrue(LiveMatchPoller.secondHalfBegun(naverSnap("후반 1'"), firstHalfEnded, now))
    }

    @Test
    @DisplayName("모르는 표기는 규정 휴식 15분이 지나야 후반으로 본다")
    fun testUnknownLabelWaitsOutTheBreak() {
        val unknown = naverSnap("2nd half")

        assertFalse(LiveMatchPoller.secondHalfBegun(unknown, firstHalfEnded, firstHalfEnded.plusMinutes(14)))
        // 뒷문을 아예 막으면 모르는 대회에서 시계가 하프타임에 갇힌다
        assertTrue(LiveMatchPoller.secondHalfBegun(unknown, firstHalfEnded, firstHalfEnded.plusMinutes(15)))
    }

    // ── 종료는 두 소스 중 먼저 아는 쪽 (8/30 세비야전) ──

    /** 그 경기 기준: 후반은 여기서 시작했다고 치고 잰다 */
    private val secondHalf = at("2026-08-30T05:35:00+09:00")

    private fun fotMobSnap(finished: Boolean) = LiveSnapshot(
        source = LiveSource.FOTMOB, status = if (finished) "경기종료" else "경기중",
        homeTeam = "Sevilla", awayTeam = "Atlético Madrid",
        homeScore = 0, awayScore = 1, competition = "LaLiga",
        goals = emptyList(), finished = finished
    )

    @Test
    @DisplayName("후반 40분이 지난 뒤 FotMob이 끝났다고 하면 네이버를 기다리지 않는다")
    fun testFinishedAheadAcceptedLate() {
        val anchor = LivePeriods(secondHalfStarted = secondHalf)
        val now = secondHalf.plusMinutes(47).toInstant()   // 92분

        assertTrue(LiveMatchPoller.finishedAhead(fotMobSnap(finished = true), anchor, now))
    }

    @Test
    @DisplayName("정규 시간이 남아 있으면 FotMob이 끝났다고 해도 안 믿는다")
    fun testFinishedAheadRejectedTooEarly() {
        val anchor = LivePeriods(secondHalfStarted = secondHalf)
        val now = secondHalf.plusMinutes(20).toInstant()   // 65분 — 끝날 수 없다

        // 8/20에 종료를 일찍 잡아 후반이 통째로 날아갔다. 그 실수는 다시 하지 않는다
        assertFalse(LiveMatchPoller.finishedAhead(fotMobSnap(finished = true), anchor, now))
    }

    @Test
    @DisplayName("기준점이 없으면 대볼 시계가 없다 — 네이버를 기다린다")
    fun testFinishedAheadNeedsClock() {
        val now = secondHalf.plusMinutes(47).toInstant()

        assertFalse(LiveMatchPoller.finishedAhead(fotMobSnap(finished = true), null, now))
        assertFalse(
            LiveMatchPoller.finishedAhead(
                fotMobSnap(finished = true),
                LivePeriods(firstHalfStarted = at("2026-08-30T04:31:00+09:00")),
                now
            )
        )
    }

    @Test
    @DisplayName("FotMob이 아직 경기중이면 아무것도 당기지 않는다")
    fun testFinishedAheadIgnoresLiveSnapshot() {
        val anchor = LivePeriods(secondHalfStarted = secondHalf)
        val now = secondHalf.plusMinutes(47).toInstant()

        assertFalse(LiveMatchPoller.finishedAhead(fotMobSnap(finished = false), anchor, now))
    }

    // ── 새 골은 FotMob 조회 간격을 건너뛴다 (8/24 비야레알전) ──

    @Test
    @DisplayName("이름 없는 새 골이 들어오면 예약된 간격을 무시하고 물어본다")
    fun testNewGoalPreemptsFotMobInterval() {
        val pending = mutableSetOf<String>()
        val goal = LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false)

        assertTrue(
            LiveMatchPoller.markUnnamedGoals(listOf(goal), emptyMap(), pending),
            "처음 보는 골이면 교체 조회 예약(45→20초)이 걸려 있어도 지금 물어봐야 한다"
        )
    }

    @Test
    @DisplayName("같은 골로는 매 턴 다시 때리지 않는다")
    fun testUnnamedGoalTriggersOnlyOnce() {
        val pending = mutableSetOf<String>()
        val goal = LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false)

        LiveMatchPoller.markUnnamedGoals(listOf(goal), emptyMap(), pending)

        // FotMob이 끝내 짝을 못 지어 주는 골이 실제로 있다. 그 하나 때문에
        // 5초마다 243KB를 받으면 안 된다 — 재시도는 LATIN_RETRY가 맡는다
        assertFalse(LiveMatchPoller.markUnnamedGoals(listOf(goal), emptyMap(), pending))
    }

    @Test
    @DisplayName("한 턴에 두 골이 들어와도 둘 다 표시된다")
    fun testMarksEveryUnnamedGoalInOneTurn() {
        val pending = mutableSetOf<String>()
        val goals = listOf(
            LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false),
            LiveGoal("안토니오 힐", 14, 0, isHome = true, ownGoal = false)
        )

        assertTrue(LiveMatchPoller.markUnnamedGoals(goals, emptyMap(), pending))
        assertEquals(2, pending.size, "첫 참에서 끊으면 두 번째 골이 표시되지 않는다")
    }

    @Test
    @DisplayName("이미 이름이 붙은 골은 조회를 부르지 않는다")
    fun testNamedGoalDoesNotTrigger() {
        val pending = mutableSetOf<String>()
        val goal = LiveGoal("훌리안 알바레스", 12, 0, isHome = true, ownGoal = false)

        assertFalse(LiveMatchPoller.markUnnamedGoals(listOf(goal), mapOf(goal.key to "Julián Álvarez"), pending))
        assertTrue(pending.isEmpty())
    }
}
