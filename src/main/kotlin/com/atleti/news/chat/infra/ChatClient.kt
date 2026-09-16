package com.atleti.news.chat.infra

/**
 * 브라우저 1대를 가리키는 익명 식별자 (닉네임 주인 판정용).
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

    /** CONNECT 때 실려 오는 관리자 토큰 (STOMP 헤더는 소문자로 맞춘다) */
    const val ADMIN_STOMP_HEADER = "x-admin-token"

    /** 그 토큰이 진짜였는지 — 판단은 서버가 CONNECT 한 번에 끝내고, 이후엔 이 값만 본다 */
    const val ADMIN_ATTRIBUTE = "chatAdmin"

    private val ALLOWED = Regex("^[A-Za-z0-9_-]{8,64}$")

    /**
     * 클라이언트가 보낸 값을 그대로 믿지 않는다. 형식이 어긋나면 null —
     */
    fun sanitize(raw: String?): String? =
        raw?.trim()?.takeIf { ALLOWED.matches(it) }
}
