package com.atleti.news.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * WebSocket + STOMP 설정 (docs/redis-design.md 6.2).
 * - 접속 지점: /ws (핸드셰이크 1회 후 연결 유지)
 * - "/app"으로 시작하는 목적지 → @MessageMapping 서버 로직으로 라우팅
 * - "/topic"으로 시작하는 목적지 → 구독자 전원에게 방송
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val ipHandshakeInterceptor: IpHandshakeInterceptor
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")   // 개발 단계 전체 허용 — 배포 전 프론트 도메인으로 제한 예정
            .addInterceptors(ipHandshakeInterceptor)   // 핸드셰이크 시 IP 캡처 (rate limit용)
            .withSockJS()                    // WebSocket 미지원 환경에서 HTTP 폴링으로 자동 대체
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // "/queue"는 개인 전달용 (정책 위반 에러를 보낸 사람에게만 회신)
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app") // 서버 로직 호출 prefix
    }
}
