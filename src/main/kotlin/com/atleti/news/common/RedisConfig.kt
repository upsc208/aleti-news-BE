package com.atleti.news.common

import com.atleti.news.chat.infra.ChatMessageRelay
import com.atleti.news.chat.infra.ChatPresenceRelay
import com.atleti.news.chat.infra.ChatPresenceTracker
import com.atleti.news.chat.domain.ChatService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

@Configuration
class RedisConfig {
    private val log = LoggerFactory.getLogger(RedisConfig::class.java)

    /**
     * Pub/Sub 구독 전용 연결 컨테이너 (docs/redis-design.md 6.2).
     * Redis의 SUBSCRIBE는 연결 하나를 통째로 점유하므로,
     * 일반 명령(GET/SET)과 별도의 연결로 채널을 상시 청취한다.
     * 리스너는 여기서 바로 등록하지 않는다 — 리스너가 있으면 기동 시 즉시
     * 연결을 시도해 Redis 없는 환경(CI/로컬)에서 앱이 못 뜨기 때문.
     * 등록은 아래 redisConnectionCheck에서 연결 확인 후 수행 (fail-open).
     */
    @Bean
    fun redisMessageListenerContainer(factory: RedisConnectionFactory) =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
        }

    /**
     * 기동 시 Redis 연결을 1회 확인하고, 성공 시에만 채팅 구독을 시작한다.
     * 연결 실패해도 앱은 정상 기동한다 — 조회수/채팅은 부가 기능이므로
     * 본 기능(기사 조회)을 막으면 안 된다 (fail-open, docs/redis-design.md 5).
     */
    @Bean
    fun redisConnectionCheck(
        factory: RedisConnectionFactory,
        container: RedisMessageListenerContainer,
        chatMessageRelay: ChatMessageRelay,
        chatPresenceRelay: ChatPresenceRelay
    ) = ApplicationRunner {
        try {
            factory.connection.use { it.ping() }
            container.addMessageListener(chatMessageRelay, ChannelTopic(ChatService.CHANNEL))
            container.addMessageListener(chatPresenceRelay, ChannelTopic(ChatPresenceTracker.PRESENCE_CHANNEL))
            log.info("✅ Redis 연결 확인 완료 — 채팅/접속자 수 구독 시작")
        } catch (e: Exception) {
            log.warn("⚠️ Redis 연결 실패 — 조회수/채팅 없이 기동합니다: {}", e.message)
        }
    }
}
