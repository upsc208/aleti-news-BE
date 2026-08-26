package com.atleti.news.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * REST 요청의 CORS. 허용 출처는 [AllowedOrigins]가 단독으로 들고 있다 —
 * WebSocket 핸드셰이크도 같은 값을 봐야 한다.
 */
@Configuration
class WebConfig(private val allowedOrigins: AllowedOrigins) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(*allowedOrigins.patterns)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}

/**
 * 브라우저에서 이 API를 부를 수 있는 출처 목록 (`atleti.web.allowed-origins`).
 *
 * **REST와 WebSocket이 반드시 같은 목록을 봐야 한다.** 따로 적어 두면 갈라진다 —
 * 실제로 REST는 도메인 다섯 개로 제한돼 있는데 WebSocket만 `"*"`로 열려 있었고,
 * 그 상태로 배포돼 있었다. 주석에는 "배포 전 제한 예정"이라고 적혀 있었다.
 *
 * `allowedOrigins`가 아니라 `allowedOriginPatterns`로 넘긴다. 정확한 문자열도 그대로
 * 동작하면서, 필요할 때 Vercel 프리뷰 같은 와일드카드 패턴을 **코드 변경 없이 설정만으로**
 * 더할 수 있다. `"*"` 하나만 넣는 것은 막는다 — 자격 증명을 허용하는 설정에서 전체 개방은
 * 의미가 없다.
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
