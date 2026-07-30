package com.atleti.news.config

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * WebSocket 핸드셰이크(최초 HTTP 요청) 시점에 클라이언트 IP를 세션에 저장한다.
 * STOMP 메시지에는 HTTP 헤더가 없어서 IP를 알 수 없으므로,
 * 유일하게 HTTP인 핸드셰이크 순간에 잡아 세션 속성으로 넘긴다 (rate limit용).
 * X-Forwarded-For 우선 — Cloudflare 뒤에서는 remoteAddress가 CF IP이기 때문 (조회수와 동일 로직).
 */
@Component
class IpHandshakeInterceptor : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean {
        attributes["ip"] = request.headers.getFirst("X-Forwarded-For")
            ?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddress?.address?.hostAddress
            ?: "unknown"
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?
    ) {
        // 사용 안 함
    }
}
