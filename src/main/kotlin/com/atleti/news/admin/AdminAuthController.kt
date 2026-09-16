package com.atleti.news.admin

import com.atleti.news.common.ClientIp
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(val username: String = "", val password: String = "")
data class LoginResponse(val token: String, val expiresInSeconds: Long)

@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController(private val adminAuth: AdminAuthService) {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest, httpRequest: HttpServletRequest): ResponseEntity<Any> =
        try {
            val token = adminAuth.login(request.username, request.password, ClientIp.of(httpRequest))
            ResponseEntity.ok(LoginResponse(token, AdminAuthService.TOKEN_TTL_SECONDS))
        } catch (e: AdminAuthException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to e.message))
        }

     //토큰이 아직 살아 있는지 — 프론트가 새로고침 때 확인
    @GetMapping("/session")
    fun session(): Map<String, Boolean> = mapOf("valid" to true)
}
