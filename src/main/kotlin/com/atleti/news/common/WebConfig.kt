package com.atleti.news.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import com.atleti.news.admin.AdminAuthInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry

/**
 * REST 요청의 CORS
 */
@Configuration
class WebConfig(
    private val allowedOrigins: AllowedOrigins,
    private val adminAuthInterceptor: AdminAuthInterceptor,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(*allowedOrigins.patterns)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }

    /**
     * 관리자 API는 로그인 토큰이나 관리자 키가 있어야 통과한다.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(adminAuthInterceptor)
            .addPathPatterns(AdminAuthInterceptor.PATH)
            .excludePathPatterns(AdminAuthInterceptor.LOGIN_PATH)
    }
}

/**
 * 브라우저에서 이 API를 부를 수 있는 출처 목록
 */
@Configuration
class AllowedOrigins(
    @Value("\${atleti.web.allowed-origins}") raw: String
) {
    val patterns: Array<String> = raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toTypedArray()

    init {
        require(patterns.isNotEmpty()) { "atleti.web.allowed-origins가 비어 있습니다" }
        require(patterns.none { it == "*" }) {
            "atleti.web.allowed-origins에 \"*\"를 넣지 않는다 — 아무 사이트나 채팅방에 붙을 수 있다"
        }
    }
}
