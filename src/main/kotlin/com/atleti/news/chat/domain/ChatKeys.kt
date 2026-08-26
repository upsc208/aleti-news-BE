package com.atleti.news.chat.domain

import java.time.Duration

/**
 * 채팅 Redis 키 (docs/redis-design.md 4장).
 *
 * 라이브 피드의 [com.atleti.news.live.domain.LiveMatchKeys]와 같은 방식이다. 키 문자열이
 * 여러 파일에 흩어져 있으면 **같은 데이터를 두 이름으로 부르는 사고**가 조용히 난다 —
 * 쓰는 쪽과 읽는 쪽이 한 글자만 달라도 아무 에러 없이 그냥 빈 값이 나온다.
 * 접두사를 바꿀 때 놓치는 곳이 생기지 않도록 한곳에서만 만든다.
 *
 * `{matchId}`는 경기 방이면 FotMob 숫자 ID, 자유 채팅방이면 [ChatService.FREE_ROOM_ID]다.
 */
object ChatKeys {

    /**
     * 최근 메시지 리스트 (`LPUSH` + `LTRIM`으로 100건 유지).
     *
     * **자유 채팅방만 TTL이 없다.** 하루 조용했다고 대화가 통째로 사라지면 "항상 열려 있는 방"이
     * 고장난 것처럼 보인다. 크기는 `LTRIM`이 고정하므로 TTL이 없어도 늘어나지 않는다
     * ([ChatService.store] 참고).
     */
    fun messages(matchId: String) = "chat:msg:$matchId"

    /** `정규화한 닉네임 → 주인 토큰` 해시. 선착순 선점과 주인 판정에 쓴다 (설계 6.5) */
    fun nicknames(matchId: String) = "chat:nick:$matchId"

    /**
     * `IP → 닉네임` 해시. 관리자 참가자 목록용 — 도배하는 사람의 차단할 IP를 찾는 통로다.
     * 지금 접속 중인 사람이 아니라 **한 번이라도 참여한 사람**이 남는다.
     */
    fun ipNicknames(matchId: String) = "chat:ipnick:$matchId"

    /**
     * 방 접속자 수 카운터 (`INCR`/`DECR`).
     * 원자 연산이라 서버가 여러 대여도 합산이 맞는다 (설계 6.7).
     */
    fun online(matchId: String) = "chat:online:$matchId"

    /**
     * IP별 전송 횟수 카운터. [RATE_LIMIT_WINDOW] TTL이 그대로 "창"이 된다 (설계 6.8).
     * 방이 아니라 **IP** 단위다 — 닉네임을 바꿔도 전송량 상한은 따라오지 않는다.
     */
    fun rateLimit(ip: String) = "chat:rl:$ip"

    /**
     * 차단된 IP 집합. 방과 무관한 전역 키이고 **TTL이 없다** —
     * 차단은 관리자가 풀기 전까지 유지돼야 한다.
     */
    const val BANNED = "chat:banned"

    /**
     * 경기 방 관련 키의 수명. 경기가 끝나면 대화도 남길 이유가 없다.
     *
     * 닉네임 대장도 같은 값을 쓴다 — 짧아야 안 쓰는 이름이 제때 풀린다.
     */
    val ROOM_TTL: Duration = Duration.ofHours(24)

    /** 도배 판정 창. 이 시간 안에 [ChatService] 상한을 넘으면 막는다 */
    val RATE_LIMIT_WINDOW: Duration = Duration.ofSeconds(10)
}
