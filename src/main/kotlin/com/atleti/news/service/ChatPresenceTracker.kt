package com.atleti.news.service

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 방별 동시 접속자 수 (docs/redis-design.md 6.7).
 * "접속"의 기준은 WebSocket 연결이 아니라 채팅방 구독(SUBSCRIBE /topic/chat/{matchId}).
 * 카운터는 Redis에 두므로(INCR/DECR 원자 연산) 서버가 여러 대여도 합산이 맞는다.
 * 세션→구독 매핑은 이 인스턴스에 붙은 세션만 알면 되므로 로컬 메모리에 둔다.
 * 비정상 종료로 카운트가 어긋나도 방 TTL(24h)로 자연 리셋 — 허용 오차 (6.7).
 */
@Component
class ChatPresenceTracker(private val redis: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(ChatPresenceTracker::class.java)

    // 이 서버 인스턴스 세션들의 구독 현황: sessionId → (subscriptionId → matchId)
    private val sessions = ConcurrentHashMap<String, MutableMap<String, String>>()

    companion object {
        private const val TOPIC_PREFIX = "/topic/chat/"
    }

    @EventListener
    fun onSubscribe(event: SessionSubscribeEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val destination = accessor.destination ?: return
        if (!destination.startsWith(TOPIC_PREFIX)) return   // 에러 채널 등 다른 구독은 무시
        val sessionId = accessor.sessionId ?: return
        val subscriptionId = accessor.subscriptionId ?: return
        val matchId = destination.removePrefix(TOPIC_PREFIX)

        sessions.computeIfAbsent(sessionId) { ConcurrentHashMap() }[subscriptionId] = matchId
        changeOnline(matchId, +1)
    }

    @EventListener
    fun onUnsubscribe(event: SessionUnsubscribeEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val sessionId = accessor.sessionId ?: return
        val subscriptionId = accessor.subscriptionId ?: return
        val matchId = sessions[sessionId]?.remove(subscriptionId) ?: return
        changeOnline(matchId, -1)
    }

    /** 브라우저 닫기/새로고침 — 남아 있던 구독 전부 정리 */
    @EventListener
    fun onDisconnect(event: SessionDisconnectEvent) {
        val subscriptions = sessions.remove(event.sessionId) ?: return
        subscriptions.values.forEach { changeOnline(it, -1) }
    }

    fun onlineCount(matchId: String): Long =
        try {
            redis.opsForValue().get("chat:online:$matchId")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            0
        }

    private fun changeOnline(matchId: String, delta: Long) {
        try {
            val key = "chat:online:$matchId"
            val value = redis.opsForValue().increment(key, delta) ?: return
            if (value < 0) redis.opsForValue().set(key, "0")   // 드리프트 방어
            redis.expire(key, Duration.ofHours(24))
        } catch (e: Exception) {
            log.warn("⚠️ 접속자 수 갱신 실패: {}", e.message)
        }
    }
}
