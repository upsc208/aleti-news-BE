package com.atleti.news.service

import com.atleti.news.dto.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Redis Pub/Sub 구독자 (docs/redis-design.md 6.2).
 * chat.messages 채널에 발행된 메시지를 받아, 이 서버에 WebSocket으로
 * 붙어 있는 구독자들에게 /topic/chat/{matchId}로 중계한다.
 * 서버가 여러 대여도 모든 인스턴스가 이 채널을 구독하므로 어느 서버에
 * 접속했든 같은 방이 된다.
 */
@Component
class ChatMessageRelay(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper
) : MessageListener {
    private val log = LoggerFactory.getLogger(ChatMessageRelay::class.java)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val chat = objectMapper.readValue(message.body, ChatMessage::class.java)
            messagingTemplate.convertAndSend("/topic/chat/${chat.matchId}", chat)
        } catch (e: Exception) {
            log.warn("⚠️ 채팅 메시지 중계 실패: {}", e.message)
        }
    }
}
