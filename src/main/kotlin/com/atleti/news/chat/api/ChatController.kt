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
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "Chat API", description = "채팅방 입장 정보 조회 및 닉네임 선점 API (메시지 전송은 WebSocket)")
@Controller
class ChatController(
    private val chatService: ChatService,
    private val presenceTracker: ChatPresenceTracker,
    private val adminAuth: AdminAuthService
) {

    // 검증(방 오픈·내용·도배) 후 Redis 저장과 발행
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

    // 정책 위반 안내는 방 전체가 아니라 보낸 사람에게만
    @MessageExceptionHandler(ChatException::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleChatError(e: ChatException): Map<String, String> =
        mapOf(
            "error" to (e.message ?: "요청을 처리할 수 없습니다."),
            "code" to e.code
        )

    @Operation(summary = "방 정보 조회", description = "오픈 여부, 접속자 수, 최근 메시지 100개, 라이브 스냅샷을 함께 돌려줍니다.")
    @GetMapping("/api/chat/{matchId}")
    @ResponseBody
    fun roomInfo(@PathVariable matchId: String): ChatRoomResponse =
        ChatRoomResponse(
            open = chatService.isOpen(matchId),
            onlineCount = presenceTracker.onlineCount(matchId),
            messages = chatService.recentMessages(matchId),
            live = chatService.liveState(matchId)
        )

    @Operation(summary = "닉네임 선점", description = "입장 전에 닉네임을 먼저 잡습니다. 중복·금칙어·닫힌 방이면 409입니다.")
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

