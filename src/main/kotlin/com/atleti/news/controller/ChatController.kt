package com.atleti.news.controller

import com.atleti.news.dto.ChatMessage
import com.atleti.news.service.ChatException
import com.atleti.news.service.ChatService
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class ChatController(private val chatService: ChatService) {

    /**
     * SEND /app/chat/{matchId} → 검증(방 오픈/내용/rate limit) 후 Redis 저장 + PUBLISH.
     * IP는 핸드셰이크 때 IpHandshakeInterceptor가 세션에 넣어둔 값을 꺼낸다.
     */
    @MessageMapping("/chat/{matchId}")
    fun send(
        @DestinationVariable matchId: String,
        message: ChatMessage,
        headerAccessor: SimpMessageHeaderAccessor
    ) {
        val ip = headerAccessor.sessionAttributes?.get("ip") as? String ?: "unknown"
        chatService.send(matchId, message.nickname, message.content, ip)
    }

    /**
     * 정책 위반(닫힌 방, 도배 등) 시 — 방 전체가 아니라 "보낸 사람에게만" 에러 회신.
     * 클라이언트는 /user/queue/errors를 구독해 받는다.
     */
    @MessageExceptionHandler(ChatException::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleChatError(e: ChatException): Map<String, String> =
        mapOf("error" to (e.message ?: "요청을 처리할 수 없습니다."))

    /** 입장 시 직전 대화 로딩용 REST — WebSocket 연결 전에 최근 100개를 먼저 그린다 */
    @GetMapping("/api/chat/{matchId}")
    @ResponseBody
    fun recentMessages(@PathVariable matchId: String): List<ChatMessage> =
        chatService.recentMessages(matchId)
}
