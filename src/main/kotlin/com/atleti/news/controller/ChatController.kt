package com.atleti.news.controller

import com.atleti.news.dto.ChatMessage
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class ChatController {

    /**
     * 클라이언트가 SEND /app/chat/{matchId}로 보낸 메시지를 받아
     * /topic/chat/{matchId} 구독자 전원에게 방송한다.
     * Phase 2a는 에코 수준 — 검증(방 오픈/닉네임/rate limit)과 Redis 기록은 Phase 2b~2c에서 추가.
     */
    @MessageMapping("/chat/{matchId}")
    @SendTo("/topic/chat/{matchId}")
    fun send(@DestinationVariable matchId: String, message: ChatMessage): ChatMessage {
        return message
    }
}
