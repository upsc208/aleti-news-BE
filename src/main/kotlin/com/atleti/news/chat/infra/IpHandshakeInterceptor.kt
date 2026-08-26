package com.atleti.news.chat.infra

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import com.atleti.news.common.ClientIp

/**
 * WebSocket 핸드셰이크(최초 HTTP 요청) 시점에 클라이언트 IP를 세션에 저장한다.
 * STOMP 메시지에는 HTTP 헤더가 없어서 IP를 알 수 없으므로,
 * 유일하게 HTTP인 핸드셰이크 순간에 잡아 세션 속성으로 넘긴다 (rate limit·차단·ipHash용).
 *
 * 추출은 [ClientIp] 한 곳에서만 한다 — REST 경로와 값이 어긋나면
 * 같은 사람이 두 사람으로 보인다.
 */
@Component
class IpHandshakeInterceptor : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean {
        attributes[ChatClient.IP_ATTRIBUTE] = ClientIp.of(request)
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
