package com.atleti.news.controller

import com.atleti.news.config.ChatClient
import com.atleti.news.config.ClientIp
import com.atleti.news.dto.ChatMessage
import com.atleti.news.dto.LiveMatchState
import com.atleti.news.service.ChatException
import com.atleti.news.service.ChatPresenceTracker
import com.atleti.news.service.ChatService
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
 *
 * [live]는 라이브 스코어보드용 스냅샷 (live-match-feed-design 6.2) — 자유 채팅방이거나
 * 네이버 경기 매핑 전이면 null이다. 프론트는 `if (room.live)`로 감싸 그리면 된다.
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
    private val presenceTracker: ChatPresenceTracker
) {

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
        val attributes = headerAccessor.sessionAttributes
        val ip = attributes?.get(ChatClient.IP_ATTRIBUTE) as? String ?: ClientIp.UNKNOWN
        val clientId = attributes?.get(ChatClient.SESSION_ATTRIBUTE) as? String
        chatService.send(matchId, message.nickname, message.content, ip, clientId)
    }

    /**
     * 정책 위반(닫힌 방, 도배 등) 시 — 방 전체가 아니라 "보낸 사람에게만" 에러 회신.
     * 클라이언트는 /user/queue/errors를 구독해 받는다.
     * code는 프론트가 사유별로 반응하기 위한 것 (NICKNAME_TAKEN이면 닉네임 모달 재오픈).
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
     * matchId는 경기 방이면 FotMob 숫자 ID, 자유 채팅방이면 "free" (ChatService.FREE_ROOM_ID).
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
     * 닉네임 사전 선점 REST — 선점 성공 시 200, 중복/금칙어/방닫힘 시 409 Conflict.
     * 이미 내가 가진 이름이면 다시 불러도 200 (멱등) — 프론트가 저장해둔 닉네임으로
     * 재입장할 때마다 호출해 "아직 내 이름이 맞는지" 확인하는 용도이기도 하다.
     */
    @PostMapping("/api/chat/{matchId}/nickname")
    @ResponseBody
    fun reserveNickname(
        @PathVariable matchId: String,
        @RequestBody request: NicknameRequest,
        @RequestHeader(name = ChatClient.HTTP_HEADER, required = false) clientId: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Any> {
        val ip = ClientIp.of(httpRequest)
        return try {
            val reserved = chatService.reserveNicknamePublic(
                matchId, request.nickname, ip, ChatClient.sanitize(clientId)
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
}
