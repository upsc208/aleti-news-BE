package com.atleti.news.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/* /api/admin (관리자 API)*/
@Component
class AdminAuthInterceptor(
    private val adminAuth: AdminAuthService,
    private val environment: Environment,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method == "OPTIONS") return true

        val token = request.getHeader(HEADER_TOKEN)
        val key = request.getHeader(HEADER_KEY)
        val adminKey = environment.getProperty("atleti.admin.key").orEmpty()

        val allowed = adminAuth.verify(token) || (adminKey.isNotBlank() && key == adminKey)
        if (allowed) return true

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"error":"관리자 인증이 필요합니다."}""")
        return false
    }

    companion object {
        const val HEADER_TOKEN = "X-Admin-Token"
        const val HEADER_KEY = "X-Admin-Key"
        const val PATH = "/api/admin/**"
        const val LOGIN_PATH = "/api/admin/auth/login"
    }
}
