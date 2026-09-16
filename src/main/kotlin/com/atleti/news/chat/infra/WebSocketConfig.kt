package com.atleti.news.chat.infra

import com.atleti.news.admin.AdminAuthService
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
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val ipHandshakeInterceptor: IpHandshakeInterceptor,
    private val adminAuth: AdminAuthService,
    private val allowedOrigins: AllowedOrigins
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(*allowedOrigins.patterns)
            .addInterceptors(ipHandshakeInterceptor)
            .withSockJS()
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app")
    }

    /**
     * CONNECT 프레임에 실려 온 브라우저 식별자를 세션에 옮겨 담는다
     */
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val accessor = StompHeaderAccessor.wrap(message)
                if (accessor.command == StompCommand.CONNECT) {
                    ChatClient.sanitize(accessor.getFirstNativeHeader(ChatClient.STOMP_HEADER))
                        ?.let { accessor.sessionAttributes?.put(ChatClient.SESSION_ATTRIBUTE, it) }
                    val adminToken = accessor.getFirstNativeHeader(ChatClient.ADMIN_STOMP_HEADER)
                    accessor.sessionAttributes?.put(ChatClient.ADMIN_ATTRIBUTE, adminAuth.verify(adminToken))
                }
                return message
            }
        })
    }
}
