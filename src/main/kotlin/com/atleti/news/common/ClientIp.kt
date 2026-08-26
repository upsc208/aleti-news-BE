package com.atleti.news.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.ServerHttpRequest

/**
 * 클라이언트 IP 추출 — REST와 WebSocket 핸드셰이크가 **반드시 같은 값**을 내야 한다.
 *
 * 예전에는 두 경로가 각자 헤더를 읽었고 fallback까지 달랐다
 * (`request.remoteAddr` vs `request.remoteAddress.address.hostAddress`).
 * 운영에는 `server.forward-headers-strategy=framework`가 켜져 있는데, 이 설정이 붙이는
 * ForwardedHeaderFilter는 값을 반영한 뒤 **X-Forwarded-For 헤더 자체를 요청에서 지운다**.
 * 그래서 두 경로가 서로 다른 fallback으로 떨어져 같은 사람이 다른 IP로 기록됐고,
 * IP를 주인으로 삼던 닉네임 선점 판정이 어긋나 "이미 사용 중인 닉네임"으로
 * 자기 이름에서 잠기는 버그가 됐다 (ChatService.reserveNickname 참고).
 *
 * 이제 두 경로 모두 이 한 곳만 쓴다. 헤더가 지워진 뒤에도 ForwardedHeaderFilter가
 * remoteAddr을 원래 클라이언트로 바꿔놓으므로 fallback만으로도 올바른 값이 나온다.
 */
object ClientIp {

    const val UNKNOWN = "unknown"

    /** 앞에 있는 헤더가 우선. Cloudflare 뒤에서는 remoteAddress가 CF IP라 헤더를 먼저 본다. */
    private val FORWARD_HEADERS = listOf("CF-Connecting-IP", "X-Real-IP", "X-Forwarded-For")

    fun of(request: HttpServletRequest): String =
        pick(FORWARD_HEADERS.map { request.getHeader(it) }, request.remoteAddr)

    fun of(request: ServerHttpRequest): String {
        val remote = request.remoteAddress
        return pick(
            FORWARD_HEADERS.map { request.headers.getFirst(it) },
            remote?.address?.hostAddress ?: remote?.hostString
        )
    }

    private fun pick(headerValues: List<String?>, remoteAddress: String?): String {
        headerValues.forEach { raw -> firstHop(raw)?.let { return it } }
        return firstHop(remoteAddress) ?: UNKNOWN
    }

    /** "client, proxy1, proxy2" 형태에서 맨 앞이 실제 클라이언트 */
    private fun firstHop(value: String?): String? =
        value?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) }
}
