package com.atleti.news.chat.api

import com.atleti.news.admin.AdminAuthInterceptor
import com.atleti.news.admin.AdminAuthService
import com.atleti.news.chat.domain.ChatParticipant
import com.atleti.news.chat.domain.ChatService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * 채팅 IP 차단 관리 (docs/redis-design.md 6.8).
 * X-Admin-Key 헤더가 서버의 ADMIN_KEY와 일치해야 호출 가능.
 * 키가 설정돼 있지 않으면 전부 거부 (기능 잠금 상태).
 */
@RestController
@RequestMapping("/api/admin/chat")
class ChatAdminController(
    private val chatService: ChatService,
    @Value("\${atleti.admin.key}") private val adminKey: String,
    private val adminAuth: AdminAuthService,
) {
    /** 로그인 토큰이거나 예전부터 쓰던 관리자 키면 통과한다 */
    private fun requireAdmin(key: String?, token: String?) {
        val allowed = adminAuth.verify(token) || (adminKey.isNotBlank() && key == adminKey)
        if (!allowed) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 인증이 필요합니다.")
        }
    }

    @GetMapping("/ban")
    fun bannedList(
        @RequestHeader(AdminAuthInterceptor.HEADER_KEY, required = false) key: String?,
        @RequestHeader(AdminAuthInterceptor.HEADER_TOKEN, required = false) token: String?,
    ): Set<String> {
        requireAdmin(key, token)
        return chatService.bannedIps()
    }

    /**
     * 방 참가자 목록 — 닉네임/ipHash와 차단에 필요한 원본 IP를 함께 내려준다.
     * 채팅 화면에는 ipHash 4자리만 보여서, 이 API 없이는 도배하는 사람을 찾아도
     * 서버에 SSH로 들어가 redis-cli를 뒤져야 IP를 알 수 있었다.
     */
    @GetMapping("/{matchId}/participants")
    fun participants(
        @RequestHeader(AdminAuthInterceptor.HEADER_KEY, required = false) key: String?,
        @RequestHeader(AdminAuthInterceptor.HEADER_TOKEN, required = false) token: String?,
        @PathVariable matchId: String
    ): List<ChatParticipant> {
        requireAdmin(key, token)
        return chatService.participants(matchId)
    }

    @PostMapping("/ban")
    fun ban(
        @RequestHeader(AdminAuthInterceptor.HEADER_KEY, required = false) key: String?,
        @RequestHeader(AdminAuthInterceptor.HEADER_TOKEN, required = false) token: String?,
        @RequestParam ip: String
    ): String {
        requireAdmin(key, token)
        chatService.ban(ip)
        return "차단 완료: $ip"
    }

    @DeleteMapping("/ban")
    fun unban(
        @RequestHeader(AdminAuthInterceptor.HEADER_KEY, required = false) key: String?,
        @RequestHeader(AdminAuthInterceptor.HEADER_TOKEN, required = false) token: String?,
        @RequestParam ip: String
    ): String {
        requireAdmin(key, token)
        chatService.unban(ip)
        return "차단 해제: $ip"
    }
}
