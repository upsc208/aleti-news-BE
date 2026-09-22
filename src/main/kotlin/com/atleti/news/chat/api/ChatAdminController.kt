package com.atleti.news.chat.api

import com.atleti.news.admin.AdminAuthInterceptor
import com.atleti.news.admin.AdminAuthService
import com.atleti.news.chat.domain.ChatParticipant
import com.atleti.news.chat.domain.ChatService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/* 채팅 참가자 조회와 IP 차단 */
@Tag(name = "Admin Chat API", description = "관리자 채팅 참가자 조회 및 IP 차단 API")
@RestController
@RequestMapping("/api/admin/chat")
class ChatAdminController(
    private val chatService: ChatService,
    @Value("\${atleti.admin.key}") private val adminKey: String,
    private val adminAuth: AdminAuthService,
) {
    private fun requireAdmin(key: String?, token: String?) {
        val allowed = adminAuth.verify(token) || (adminKey.isNotBlank() && key == adminKey)
        if (!allowed) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 인증이 필요합니다.")
        }
    }

    @Operation(summary = "차단 목록 조회", description = "현재 차단된 IP 전부를 돌려줍니다.")
    @GetMapping("/ban")
    fun bannedList(
        @RequestHeader(AdminAuthInterceptor.HEADER_KEY, required = false) key: String?,
        @RequestHeader(AdminAuthInterceptor.HEADER_TOKEN, required = false) token: String?,
    ): Set<String> {
        requireAdmin(key, token)
        return chatService.bannedIps()
    }

    @Operation(
        summary = "방 참가자 목록",
        description = "이 방에서 닉네임을 정한 사람들의 닉네임·ipHash와 차단에 필요한 원본 IP를 돌려줍니다."
    )
    @GetMapping("/{matchId}/participants")
    fun participants(
        @RequestHeader(AdminAuthInterceptor.HEADER_KEY, required = false) key: String?,
        @RequestHeader(AdminAuthInterceptor.HEADER_TOKEN, required = false) token: String?,
        @PathVariable matchId: String
    ): List<ChatParticipant> {
        requireAdmin(key, token)
        return chatService.participants(matchId)
    }

    @Operation(summary = "IP 차단", description = "해당 IP의 채팅 전송과 입장을 막습니다. 관리자가 풀기 전까지 유지됩니다.")
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

    @Operation(summary = "IP 차단 해제", description = "차단 목록에서 해당 IP를 지웁니다.")
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
