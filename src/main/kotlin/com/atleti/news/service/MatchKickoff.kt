package com.atleti.news.service

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 저장된 킥오프 문자열 → 시각.
 *
 * `upcoming_match.matchDate` / `match_result.matchDate`는 [MatchScraperService]가
 * ISO_OFFSET(KST)으로 넣지만, 옛 기록에는 오프셋 없는 형식도 섞여 있다.
 * 채팅방 오픈 판정과 라이브 폴링 구간이 **같은 시각**을 봐야 해서 한곳에 모아 둔다.
 */
object MatchKickoff {
    private val KST = ZoneId.of("Asia/Seoul")

    fun parse(raw: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(raw) }
            .recoverCatching { Instant.parse(raw.replace(" ", "T")).atZone(KST).toOffsetDateTime() }
            .getOrNull()
}
