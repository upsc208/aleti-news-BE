package com.atleti.news.controller

import com.atleti.news.dto.ChatMessage
import com.atleti.news.service.ChatService
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class ChatController(private val chatService: ChatService) {

    /**
     * SEND /app/chat/{matchId} → Redis에 저장 + PUBLISH (docs/redis-design.md 6.2).
     * Phase 2a의 @SendTo(직접 방송)는 제거 — 방송은 이제 Redis Pub/Sub을 경유해
     * ChatMessageRelay가 담당한다. 검증(방 오픈/닉네임/rate limit)은 Phase 2c에서 추가.
     */
    @MessageMapping("/chat/{matchId}")
    fun send(@DestinationVariable matchId: String, message: ChatMessage) {
        chatService.send(matchId, message.nickname, message.content)
    }

    /** 입장 시 직전 대화 로딩용 REST — WebSocket 연결 전에 최근 100개를 먼저 그린다 */
    @GetMapping("/api/chat/{matchId}")
    @ResponseBody
    fun recentMessages(@PathVariable matchId: String): List<ChatMessage> =
        chatService.recentMessages(matchId)
}
