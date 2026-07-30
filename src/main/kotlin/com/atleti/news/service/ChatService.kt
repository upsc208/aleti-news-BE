package com.atleti.news.service

import com.atleti.news.dto.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 채팅 발행/조회 (docs/redis-design.md 6.2).
 * 메시지 1건 발행 시 Redis에서 일어나는 일:
 *   1) LPUSH chat:msg:{matchId}  — 최근 메시지 목록 맨 앞에 저장
 *   2) LTRIM 0 99                — 100개 초과분은 뒤에서 잘라냄
 *   3) EXPIRE 24h                — 방 전체가 24시간 뒤 자동 소멸
 *   4) PUBLISH chat.messages     — 구독 중인 모든 앱 인스턴스에 방송
 */
@Service
class ChatService(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val CHANNEL = "chat.messages"   // Pub/Sub 채널 (단일 채널, 페이로드에 matchId 포함)
        private const val MAX_RECENT = 100L
    }

    fun send(matchId: String, nickname: String, content: String) {
        val message = ChatMessage(
            matchId = matchId,
            nickname = nickname.trim(),
            content = content.trim(),
            sentAt = OffsetDateTime.now()
        )
        val json = objectMapper.writeValueAsString(message)
        val key = "chat:msg:$matchId"

        redis.opsForList().leftPush(key, json)
        redis.opsForList().trim(key, 0, MAX_RECENT - 1)
        redis.expire(key, Duration.ofHours(24))
        redis.convertAndSend(CHANNEL, json)
    }

    /** 입장 시 보여줄 최근 메시지 — 오래된 것부터 (화면에 그리는 순서 그대로) */
    fun recentMessages(matchId: String): List<ChatMessage> =
        (redis.opsForList().range("chat:msg:$matchId", 0, MAX_RECENT - 1) ?: emptyList())
            .map { objectMapper.readValue(it, ChatMessage::class.java) }
            .reversed()   // LPUSH 구조상 최신이 맨 앞이므로 뒤집는다
}
