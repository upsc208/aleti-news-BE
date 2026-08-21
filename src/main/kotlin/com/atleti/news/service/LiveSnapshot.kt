package com.atleti.news.service

import com.atleti.news.dto.LivePeriods
import com.atleti.news.dto.LiveSubstitution

/**
 * 라이브 정보를 어디서 가져왔는지 (docs/live-match-feed-design.md 8.3).
 * 두 소스는 선수 이름 표기가 서로 달라(한글/영문) 골 키가 호환되지 않는다.
 * 소스가 바뀌는 순간을 알아야 이미 알린 골을 다시 알리지 않는다.
 */
enum class LiveSource { NAVER, FOTMOB }

/** 득점 한 건. 소스와 무관한 형태 */
data class LiveGoal(
    val name: String,
    val time: Int,
    /** 추가시간(분). 없으면 0 */
    val addedTime: Int,
    /** 골을 **넣은 선수**가 홈 소속인지. 자책골이면 점수는 반대편에 올라간다 */
    val isHome: Boolean,
    val ownGoal: Boolean,
    /**
     * 같은 선수의 라틴 표기 (`Julián Álvarez`). 네이버는 한글만 주므로 FotMob에서 채워 넣는다.
     * 아직 못 찾았으면 null — 프론트가 자체 표로 넘어간다.
     *
     * **[key]에는 들어가지 않는다.** 이름이 뒤늦게 채워진다고 골 키가 바뀌면
     * 이미 알린 골을 다시 알린다.
     */
    val nameLatin: String? = null
) {
    /**
     * 골 하나를 고유 식별하는 키. 두 소스 모두 FotMob의 `eventId` 같은 고유값을 안 주므로 합성한다.
     * 중복 알림 방지와 VAR 취소 감지가 이 키의 집합 비교로 이뤄진다 (설계 5.3).
     *
     * 예) `A|43+0|호르헤 도밍게스|false`
     */
    val key: String get() = "${if (isHome) "H" else "A"}|$time+$addedTime|$name|$ownGoal"

    /** 표시용 분 표기. 예) `43'`, `45+2'` */
    val minute: String get() = if (addedTime > 0) "$time+$addedTime'" else "$time'"

    /** 소스가 달라도 비교할 수 있는 절대 분 — 이름 표기가 달라 이름으로는 못 맞춘다 */
    val totalMinute: Int get() = time + addedTime

    companion object {
        /**
         * [key]를 되돌린다. VAR 취소는 "응답에서 항목이 사라지는" 형태라, 정정 메시지에 쓸
         * 득점자 정보가 이미 없다. 남아 있는 건 Redis 집합에 든 키뿐이므로 거기서 복원한다.
         *
         * 형식이 안 맞으면 null — 키 형식을 바꾼 배포 직후 옛 키가 섞여 들어와도 터지지 않는다.
         */
        fun fromKey(key: String): LiveGoal? {
            val parts = key.split("|")
            if (parts.size < 4) return null
            val (time, addedTime) = parts[1].split("+", limit = 2).takeIf { it.size == 2 } ?: return null

            return LiveGoal(
                // 이름이 구분자를 품고 있어도 되도록 양 끝을 먼저 떼고 남는 것을 이름으로 본다
                name = parts.subList(2, parts.size - 1).joinToString("|"),
                time = time.toIntOrNull() ?: return null,
                addedTime = addedTime.toIntOrNull() ?: return null,
                isHome = parts[0] == "H",
                ownGoal = parts.last().toBoolean()
            )
        }
    }
}

/**
 * 폴러가 소스와 무관하게 다루는 경기 상태.
 * 네이버든 FotMob이든 이 형태로 바꿔서 넘긴다.
 */
data class LiveSnapshot(
    val source: LiveSource,
    /** 화면에 그대로 쓰는 한글 상태. 예: `경기전`, `경기중`, `전반종료`, `경기종료` */
    val status: String,
    /**
     * 소스가 준 원본 상태 코드. 화면에는 안 나가고 **로그로만** 쓴다.
     *
     * 8/20 경기에서 하프타임 표기가 `전반종료`인 것은 알아냈지만 그때의 코드는 기록이 없어
     * 종료 판정을 방어적으로 짤 수밖에 없었다. 다음 경기에서는 남게 한다.
     */
    val statusCode: String = "",
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    /** 대회명. 네이버는 한글(`라리가`), FotMob은 라틴(`LaLiga`)으로 준다 */
    val competition: String,
    val goals: List<LiveGoal>,
    /** 선수 교체. FotMob만 준다 — 네이버 스냅샷에서는 항상 비어 있다 */
    val substitutions: List<LiveSubstitution> = emptyList(),
    /** 폴링을 멈춰도 되는 상태인지 (종료·취소·연기) */
    val finished: Boolean,
    /**
     * 하프타임인지. 네이버만 알려준다 — FotMob 스냅샷에서는 항상 false다.
     *
     * FotMob의 `halfs.firstHalfEnded`가 안 올 때 시계를 멈출 근거가 된다
     * (2026-08-20 말라가전에서 실제로 끝내 안 왔다).
     */
    val halftime: Boolean = false,
    /** 경기 시계 기준점. FotMob만 준다 — 네이버 스냅샷에서는 null */
    val periods: LivePeriods? = null
)
