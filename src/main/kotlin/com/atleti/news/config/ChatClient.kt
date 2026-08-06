package com.atleti.news.config

/**
 * 브라우저 1대를 가리키는 익명 식별자 (닉네임 주인 판정용).
 *
 * 왜 IP가 아니라 이 값인가 — IP는 사람을 가리키지 못한다.
 * 공유기·카페·회사망은 여러 사람이 한 IP를 쓰고, 반대로 모바일(CGNAT)이나
 * 재접속·IPv6 환경에서는 같은 사람의 IP가 수시로 바뀐다. IP로 닉네임 주인을 정하면
 * 후자에서 **자기가 쓰던 이름에 자기가 잠기고**, 프론트는 이미 저장된 닉네임이 있어
 * 입력 모달을 건너뛰므로 이름을 바꿀 방법도 없이 채팅이 막힌다.
 *
 * 그래서 프론트가 localStorage에 UUID를 하나 만들어 두고 REST 헤더와 STOMP CONNECT 헤더로
 * 함께 보낸다. IP는 rate limit·차단·ipHash 용도로만 계속 쓴다.
 */
object ChatClient {

    /** REST 요청 헤더 이름 (HTTP 헤더라 대소문자 무관) */
    const val HTTP_HEADER = "X-Chat-Client-Id"

    /** STOMP CONNECT 프레임의 native 헤더 이름 (STOMP 헤더는 대소문자를 구분한다) */
    const val STOMP_HEADER = "x-chat-client-id"

    /** WebSocket 세션 속성 키 — CONNECT 때 담아두고 이후 SEND에서 꺼내 쓴다 */
    const val SESSION_ATTRIBUTE = "chatClientId"

    /** 핸드셰이크 때 담아두는 IP 세션 속성 키 */
    const val IP_ATTRIBUTE = "ip"

    private val ALLOWED = Regex("^[A-Za-z0-9_-]{8,64}$")

    /**
     * 클라이언트가 보낸 값을 그대로 믿지 않는다. 형식이 어긋나면 null —
     * 호출부는 null일 때 예전처럼 IP를 주인으로 삼는다 (구버전 프론트 호환).
     */
    fun sanitize(raw: String?): String? =
        raw?.trim()?.takeIf { ALLOWED.matches(it) }
}
