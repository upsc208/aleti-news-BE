package com.atleti.news.chat.infra

import com.atleti.news.common.AllowedOrigins
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
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
    private val ipHandshakeInterceptor: IpHandshakeInterceptor,
    private val allowedOrigins: AllowedOrigins
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            // REST CORS와 **같은 목록**을 쓴다 (AllowedOrigins). 전에는 여기만 "*"였다 —
            // 아무 사이트나 우리 채팅방에 붙어 읽고 쓸 수 있는 상태로 배포돼 있었다
            .setAllowedOriginPatterns(*allowedOrigins.patterns)
            .addInterceptors(ipHandshakeInterceptor)   // 핸드셰이크 시 IP 캡처 (rate limit용)
            .withSockJS()                    // WebSocket 미지원 환경에서 HTTP 폴링으로 자동 대체
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // "/queue"는 개인 전달용 (정책 위반 에러를 보낸 사람에게만 회신)
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app") // 서버 로직 호출 prefix
    }

    /**
     * CONNECT 프레임에 실려 온 브라우저 식별자를 세션에 옮겨 담는다 ([ChatClient]).
     *
     * SockJS 핸드셰이크는 브라우저가 커스텀 헤더를 붙일 수 없어서 IP처럼 핸드셰이크에서
     * 받을 수 없다. 반면 STOMP CONNECT는 클라이언트가 헤더를 자유롭게 실어 보낼 수 있다.
     * 여기서 한 번 세션 속성에 넣어두면 이후 SEND 프레임에서 그대로 꺼내 쓸 수 있다
     * (sessionAttributes는 WebSocket 세션의 맵 그 자체라 연결 내내 유지된다).
     */
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val accessor = StompHeaderAccessor.wrap(message)
                if (accessor.command == StompCommand.CONNECT) {
                    ChatClient.sanitize(accessor.getFirstNativeHeader(ChatClient.STOMP_HEADER))
                        ?.let { accessor.sessionAttributes?.put(ChatClient.SESSION_ATTRIBUTE, it) }
                }
                return message
            }
        })
    }
}
