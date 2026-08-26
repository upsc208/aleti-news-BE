package com.atleti.news.chat.infra

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * 접속자 수 변동 중계 (ChatMessageRelay의 presence 판박이).
 * chat.presence 채널을 듣고, 이 서버에 붙어 있는 구독자에게
 * /topic/presence/{matchId}로 밀어준다.
 *
 * 모든 인스턴스가 같은 채널을 구독하므로 서버가 여러 대여도 숫자가 일치한다.
 */
@Component
class ChatPresenceRelay(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper
) : MessageListener {
    private val log = LoggerFactory.getLogger(ChatPresenceRelay::class.java)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val event = objectMapper.readValue(message.body, PresenceEvent::class.java)
            messagingTemplate.convertAndSend("/topic/presence/${event.matchId}", event)
        } catch (e: Exception) {
            log.warn("⚠️ 접속자 수 중계 실패: {}", e.message)
        }
    }
}
