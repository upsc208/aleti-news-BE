package com.atleti.news.service

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
}
