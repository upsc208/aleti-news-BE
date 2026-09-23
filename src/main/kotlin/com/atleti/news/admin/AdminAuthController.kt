package com.atleti.news.admin

import com.atleti.news.admin.dto.LoginRequest
import com.atleti.news.admin.dto.LoginResponse
import com.atleti.news.common.ClientIp
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* 관리자 로그인 */
@Tag(name = "Admin Auth API", description = "관리자 로그인 및 세션 확인 API")
@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController(private val adminAuth: AdminAuthService) {

    @Operation(summary = "관리자 로그인", description = "아이디·비밀번호가 맞으면 12시간짜리 토큰을 발급합니다. 실패가 쌓이면 IP 단위로 잠깁니다.")
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest, httpRequest: HttpServletRequest): ResponseEntity<Any> =
        try {
            val token = adminAuth.login(request.username, request.password, ClientIp.of(httpRequest))
            ResponseEntity.ok(LoginResponse(token, AdminAuthService.TOKEN_TTL_SECONDS))
        } catch (e: AdminAuthException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to e.message))
        }

    @Operation(summary = "세션 확인", description = "토큰이 아직 살아 있는지 확인합니다. 토큰이 없거나 만료면 401입니다.")
    @GetMapping("/session")
    fun session(): Map<String, Boolean> = mapOf("valid" to true)
}
