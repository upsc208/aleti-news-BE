package com.atleti.news.news.domain

import com.atleti.news.news.domain.YoutubeService.Companion.joinTranscript
import com.atleti.news.news.domain.YoutubeService.Companion.parseLatestVideo
import com.atleti.news.news.domain.YoutubeService.Companion.parseToSpainTime
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 유튜브 파이프라인의 해석 로직 검증. **뉴스 도메인의 첫 테스트다.**
 *
 * 전에는 셋 다 네트워크 호출 안에 묶여 있었다. 특히 상대 날짜 파싱은 안에서 `Instant.now()`를
 * 불러 **같은 입력에도 결과가 매번 달라져** 단정할 수가 없었다. 시각을 인자로 받게 바꾸면서
 * 검증 가능해졌다 (docs/architecture.md 3.3).
 *
 * 여기서 지키는 값은 기사의 **발행 시각**이다. 화면이 최신순으로 정렬하는 기준이라
 * 잘못 계산되면 오래된 영상이 맨 위에 뜨거나 새 영상이 묻힌다.
 */
class YoutubeParsingTest {

    /** 스페인 서머타임 구간 (UTC+2). 시간대 변환이 실제로 일어나는지 보려면 UTC와 달라야 한다 */
    private val now: Instant = Instant.parse("2026-08-26T12:00:00Z")

    private fun spain(iso: String) = OffsetDateTime.parse(iso)

    // ── 상대 날짜 → 실제 시각 ────────────────────────────────────────────

    @Test
    @DisplayName("영어 표기를 읽는다")
    fun testEnglishRelativeDates() {
        assertEquals(spain("2026-08-26T11:00+02:00"), parseToSpainTime("3 hours ago", now))
        assertEquals(spain("2026-08-24T14:00+02:00"), parseToSpainTime("2 days ago", now))
        assertEquals(spain("2026-08-26T13:30+02:00"), parseToSpainTime("30 minutes ago", now))
        assertEquals(spain("2026-08-19T14:00+02:00"), parseToSpainTime("1 week ago", now))
    }

    @Test
    @DisplayName("한국어 표기를 읽는다 — 스크래퍼가 ko-KR로 요청한다")
    fun testKoreanRelativeDates() {
        // fetchLatestLiveVideoInfo가 Accept-Language: ko-KR로 요청하므로 실제로 한국어가 온다
        assertEquals(spain("2026-08-26T11:00+02:00"), parseToSpainTime("3시간 전", now))
        assertEquals(spain("2026-08-24T14:00+02:00"), parseToSpainTime("2일 전", now))
        assertEquals(spain("2026-08-26T13:30+02:00"), parseToSpainTime("30분 전", now))
        assertEquals(spain("2026-08-19T14:00+02:00"), parseToSpainTime("1주 전", now))
    }

    @Test
    @DisplayName("\"1주일 전\"을 하루 전으로 읽지 않는다")
    fun testWeekBeatsDayInKorean() {
        // "주일"에는 "일"이 들어 있다. 판정 순서에서 주(week)가 일(day)보다 뒤에 있으면
        // 이 입력이 day 분기에 먼저 걸려 6일이 어긋난다
        assertEquals(spain("2026-08-19T14:00+02:00"), parseToSpainTime("1주일 전", now))
    }

    @Test
    @DisplayName("UTC를 스페인 시간대로 옮긴다")
    fun testConvertsToSpainZone() {
        // 서머타임 구간이라 UTC+2. 이 변환이 빠지면 기사 시각이 두 시간 어긋난다
        val result = parseToSpainTime("0 minutes ago", now)
        assertEquals(spain("2026-08-26T14:00+02:00"), result)
    }

    @Test
    @DisplayName("모르는 표기는 지금 시각으로 둔다")
    fun testUnknownRelativeFallsBackToNow() {
        // "1개월 전" 같은 표기. 최신 영상 하나만 보는 파이프라인이라 실제로 걸릴 일이 드물고,
        // 지어낸 과거 시각보다 지금이 낫다
        assertEquals(spain("2026-08-26T14:00+02:00"), parseToSpainTime("1개월 전", now))
        assertEquals(spain("2026-08-26T14:00+02:00"), parseToSpainTime("", now))
    }

    @Test
    @DisplayName("숫자가 없으면 0으로 친다")
    fun testNoNumberMeansZero() {
        assertEquals(spain("2026-08-26T14:00+02:00"), parseToSpainTime("hours ago", now))
    }

    // ── 스트림 탭 HTML 파싱 ──────────────────────────────────────────────

    @Test
    @DisplayName("가장 위에 있는 영상을 고른다 — 유튜브가 최신순으로 그린다")
    fun testPicksFirstVideo() {
        val html = """
            {"videoId":"NEWEST_ID","publishedTimeText":{"simpleText":"3시간 전"}}
            {"videoId":"OLDER_ID","publishedTimeText":{"simpleText":"2일 전"}}
        """.trimIndent()

        assertEquals("NEWEST_ID" to "3시간 전", parseLatestVideo(html))
    }

    @Test
    @DisplayName("발행일을 못 찾으면 방금 올라온 것으로 본다")
    fun testMissingDateDefaults() {
        val html = """{"videoId":"ABC123","title":"라이브"}"""
        assertEquals("ABC123" to "0 minutes ago", parseLatestVideo(html))
    }

    @Test
    @DisplayName("영상 ID가 없으면 null — 파이프라인이 여기서 멈춘다")
    fun testNoVideoId() {
        assertNull(parseLatestVideo("""{"contents":[]}"""))
        assertNull(parseLatestVideo(""))
    }

    // ── 자막 조립 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("자막 조각을 공백으로 잇는다")
    fun testJoinTranscript() {
        val response = mapOf<String, Any>(
            "transcript" to listOf(
                mapOf("snippet" to "Hola", "start" to 0),
                mapOf("snippet" to "buenas", "start" to 2),
                mapOf("snippet" to "tardes", "start" to 4)
            )
        )
        assertEquals("Hola buenas tardes", joinTranscript(response))
    }

    @Test
    @DisplayName("자막이 없으면 null — 아직 생성 안 된 영상이다")
    fun testNoTranscript() {
        assertNull(joinTranscript(mapOf("error" to "not available")))
        assertNull(joinTranscript(emptyMap()))
    }

    @Test
    @DisplayName("빈 자막 배열은 빈 문자열")
    fun testEmptyTranscriptArray() {
        // 호출부가 isNullOrBlank로 걸러 파이프라인을 멈춘다
        assertEquals("", joinTranscript(mapOf("transcript" to emptyList<Map<String, Any>>())))
    }
}
