package com.atleti.news.dto

import java.time.OffsetDateTime

/**
 * 라이브 경기 스냅샷 (docs/live-match-feed-design.md 6.1).
 *
 * **항상 전체 상태다.** "새로 들어간 골"만 담지 않는다 — 프론트는 받을 때마다 통째로
 * 다시 그리면 되고, VAR로 점수가 되돌아가는 경우도 누적 계산 없이 자연히 처리된다.
 * 재연결로 메시지를 놓쳐도 `GET /api/chat/{matchId}` 한 번이면 스코어보드가 복구된다.
 */
data class LiveMatchState(
    /** 네이버가 주는 한글 상태. 예: `경기전`, `경기중`, `경기종료` */
    val status: String,
    /** 한글 팀명. 채팅·헤더에 그대로 쓴다 */
    val homeTeam: String,
    val awayTeam: String,
    /**
     * 같은 팀의 라틴 표기 (`Real Sociedad`) — 경기장 LED처럼 로마자로 그려야 하는 자리용.
     * 득점자 이름과 같은 이유로 FotMob에서 가져온다. 못 받았으면 null.
     */
    val homeTeamLatin: String? = null,
    val awayTeamLatin: String? = null,
    /** 대회명 (`라리가`). 화면 헤더용 한글 표기 */
    val competition: String? = null,
    /** 같은 대회의 라틴 표기 (`LaLiga`) — 보드용 */
    val competitionLatin: String? = null,
    val homeScore: Int,
    val awayScore: Int,
    val scorers: List<LiveScorer>,
    /**
     * 이 경기의 킥오프 예정 시각. 화면이 경기 시계를 세는 기준점이다.
     *
     * 5초마다 시계를 밀어 줄 수는 없으므로(그러려면 초당 폴링이 필요하다) 기준점만 주고
     * 세는 것은 화면이 한다. **예정 시각이라 실제 킥오프가 늦어지면 그만큼 앞선다** —
     * 정확한 시계는 소스가 주는 값이 필요한데 아직 확인 전이다 (설계 9.2).
     */
    val kickoff: OffsetDateTime?,
    /** 실제 경기 시계 기준점. 있으면 [kickoff] 대신 이걸로 세는 것이 정확하다 */
    val periods: LivePeriods? = null,
    val updatedAt: OffsetDateTime
)

/**
 * 경기 시계의 기준점 — **실제로 공이 굴러가기 시작한 시각**.
 *
 * 예정 킥오프로 세면 어긋난다: 킥오프는 보통 몇 분 늦고, 하프타임은 15분이 아니며,
 * 전반 추가시간만큼 후반 시작이 밀린다. 이 값들이 있으면 그 셋이 한 번에 사라진다.
 *
 * 후반 시계 = `45:00 + (지금 − secondHalfStarted)`.
 *
 * FotMob만 주므로 없을 수 있다. 그때는 화면이 예정 킥오프로 어림한다.
 */
data class LivePeriods(
    val firstHalfStarted: OffsetDateTime? = null,
    val firstHalfEnded: OffsetDateTime? = null,
    val secondHalfStarted: OffsetDateTime? = null
)

/** 득점 한 건. [isHome]과 [ownGoal]을 함께 봐야 어느 팀 득점인지 정해진다. */
data class LiveScorer(
    /** 한글 표기. 채팅·골 카드에 그대로 쓴다 */
    val name: String,
    val time: Int,
    /** 추가시간(분). 없으면 0 */
    val addedTime: Int,
    /** 골을 **넣은 선수**가 홈 소속인지. 자책골이면 점수는 반대편에 올라간다 */
    val isHome: Boolean,
    val ownGoal: Boolean,
    /**
     * 같은 선수의 라틴 표기 (`Julián Álvarez`) — 경기장 LED처럼 로마자로 그려야 하는 자리용.
     *
     * 네이버는 한글만 주므로 FotMob의 같은 골에서 가져온다. 못 찾았으면 null이고,
     * 그때는 화면이 자체 표로 넘어간다. **표기를 고르는 건 화면 몫이다** —
     * 채팅은 한글, 보드는 라틴으로 같은 골을 다르게 그린다.
     */
    val nameLatin: String? = null
)
