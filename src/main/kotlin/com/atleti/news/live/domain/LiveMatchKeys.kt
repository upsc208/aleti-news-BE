package com.atleti.news.live.domain

import java.time.Duration

/**
 * 라이브 피드 Redis 키 (docs/live-match-feed-design.md 5.2).
 *
 * 폴러([LiveMatchPoller])와 조회 경로([ChatService.liveState])가 같은 이름을 봐야 하므로
 * 한곳에 모아 둔다. `{matchId}`는 전부 **FotMob ID**다 — 네이버 gameId는 [map] 값에만 있고
 * 방 키로는 쓰지 않는다 (설계 6.7-1).
 */
object LiveMatchKeys {

    /** 폴러 리더 락. 값은 인스턴스 식별자 — 자기 락이면 이어서 잡는다 */
    fun lock(matchId: String) = "live:poll:$matchId"

    /** 현재 스코어 + 득점자 스냅샷 (JSON). 입장자는 네이버가 아니라 이걸 읽는다 */
    fun state(matchId: String) = "live:state:$matchId"

    /** 발행 완료한 골 키 집합. 중복 알림 방지 + VAR 취소 감지 */
    fun goals(matchId: String) = "live:goals:$matchId"

    /**
     * [goals]에 든 키를 만든 소스 (`NAVER` / `FOTMOB`).
     * 설계 표에 없는 키지만, 골 키에 선수 이름이 들어가는데 두 소스의 표기가 달라
     * (한글/영문) 소스가 바뀐 순간을 모르면 이미 알린 골 전부를 다시 알린다.
     */
    fun source(matchId: String) = "live:src:$matchId"

    /** FotMob matchId → 네이버 gameId */
    fun map(matchId: String) = "live:map:$matchId"

    /**
     * 매핑 실패 표식.
     * 설계 표에 없는 키지만, 이게 없으면 짝을 못 찾은 경기에 대해 **5초마다** 월간 캘린더
     * 전체를 다시 훑는다(경기 상세 수십 건). 실패를 잠시 기억해 두는 쪽이 훨씬 싸다.
     */
    fun mapMiss(matchId: String) = "live:map:miss:$matchId"

    /**
     * 경기 종료를 처음 감지한 시각(epoch ms).
     * 설계 표에 없는 키지만, "종료 +10분에 멈춘다"(5.4)를 재시작·다중 인스턴스에서도
     * 지키려면 이 시각이 인스턴스 메모리가 아니라 Redis에 있어야 한다.
     */
    fun end(matchId: String) = "live:end:$matchId"

    /** 경기 하나가 3시간이므로 6h면 종료 후 조회까지 넉넉히 덮는다 */
    val LIVE_TTL: Duration = Duration.ofHours(6)

    /**
     * 매핑 수명. 설계 표는 6h지만 24h로 둔다 — 매핑은 킥오프 9시간 전(19시 배치)에
     * 만들어질 수 있어 6h면 정작 킥오프 때 만료돼 있다.
     */
    val MAP_TTL: Duration = Duration.ofHours(24)

    /** 실패를 기억해 두는 시간. 이 시간이 지나면 다시 한 번 짝을 찾아본다 */
    val MAP_MISS_TTL: Duration = Duration.ofMinutes(30)
}
