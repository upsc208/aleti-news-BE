package com.atleti.news.chat.api

import com.atleti.news.admin.AdminAuthInterceptor
import com.atleti.news.admin.AdminAuthService
import com.atleti.news.chat.infra.ChatClient
import com.atleti.news.common.ClientIp
import com.atleti.news.chat.domain.ChatMessage
import com.atleti.news.live.domain.LiveMatchState
import com.atleti.news.chat.domain.ChatException
import com.atleti.news.chat.infra.ChatPresenceTracker
import com.atleti.news.chat.domain.ChatService
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.stereotype.Controller
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 방 정보 응답 (docs/redis-design.md 6.3): 오픈 여부 + 접속자 수 + 최근 메시지.
 */
data class ChatRoomResponse(
    val open: Boolean,
    val onlineCount: Long,
    val messages: List<ChatMessage>,
    val live: LiveMatchState? = null
)

data class NicknameRequest(val nickname: String = "")
data class NicknameResponse(val nickname: String, val status: String = "RESERVED")

@Controller
class ChatController(
    private val chatService: ChatService,
    private val presenceTracker: ChatPresenceTracker,
    private val adminAuth: AdminAuthService
) {

    /**
     * SEND /app/chat/{matchId} → 검증(방 오픈/내용/rate limit) 후 Redis 저장 + PUBLISH.
     */
    @MessageMapping("/chat/{matchId}")
    fun send(
        @DestinationVariable matchId: String,
        message: ChatMessage,
        headerAccessor: SimpMessageHeaderAccessor
    ) {
        val attributes = headerAccessor.sessionAttributes
        val ip = attributes?.get(ChatClient.IP_ATTRIBUTE) as? String ?: ClientIp.UNKNOWN
        val clientId = attributes?.get(ChatClient.SESSION_ATTRIBUTE) as? String
        val admin = attributes?.get(ChatClient.ADMIN_ATTRIBUTE) == true

        if (admin && message.content.startsWith(LED_COMMAND)) {
            chatService.sendLedMessage(matchId, message.content.removePrefix(LED_COMMAND), ip)
            return
        }
        chatService.send(matchId, message.nickname, message.content, ip, clientId, admin)
    }

    /**
     * 정책 위반(닫힌 방, 도배 등) 시 — 방 전체가 아니라 "보낸 사람에게만" 에러 보냄
     */
    @MessageExceptionHandler(ChatException::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleChatError(e: ChatException): Map<String, String> =
        mapOf(
            "error" to (e.message ?: "요청을 처리할 수 없습니다."),
            "code" to e.code
        )

    /**
     * 입장 시 방 정보 로딩용 REST — 오픈 여부, 접속자 수, 최근 메시지 100개.
     */
    @GetMapping("/api/chat/{matchId}")
    @ResponseBody
    fun roomInfo(@PathVariable matchId: String): ChatRoomResponse =
        ChatRoomResponse(
            open = chatService.isOpen(matchId),
            onlineCount = presenceTracker.onlineCount(matchId),
            messages = chatService.recentMessages(matchId),
            live = chatService.liveState(matchId)
        )

    /**
     * 닉네임 사전 선점 REST — 선점 성공 시 200, 중복/금칙어/방닫힘 시 409 Conflict
     */
    @PostMapping("/api/chat/{matchId}/nickname")
    @ResponseBody
    fun reserveNickname(
        @PathVariable matchId: String,
        @RequestBody request: NicknameRequest,
        @RequestHeader(name = ChatClient.HTTP_HEADER, required = false) clientId: String?,
        @RequestHeader(name = AdminAuthInterceptor.HEADER_TOKEN, required = false) adminToken: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Any> {
        val ip = ClientIp.of(httpRequest)
        return try {
            val reserved = chatService.reserveNicknamePublic(
                matchId, request.nickname, ip, ChatClient.sanitize(clientId), adminAuth.verify(adminToken)
            )
            ResponseEntity.ok(NicknameResponse(nickname = reserved))
        } catch (e: ChatException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf(
                    "error" to (e.message ?: "닉네임 선점에 실패했습니다."),
                    "code" to e.code
                )
            )
        }
    }

    companion object {
        const val LED_COMMAND = "/"
    }
}

