package com.atleti.news.chat.infra

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 채팅 WebSocket에 **아무 사이트나 붙을 수 없는지** 실제로 확인한다.
 *
 * 전에는 `setAllowedOriginPatterns("*")`였다. 주석에는 "배포 전 프론트 도메인으로 제한 예정"이라고
 * 적혀 있었지만 그대로 배포돼 있었고, 그 상태에서는 임의의 웹사이트가 방문자의 브라우저로
 * 우리 채팅방에 붙어 대화를 읽고 메시지를 보낼 수 있었다.
 *
 * 설정 파일만 고치고 "됐겠지" 하면 안 되는 종류의 변경이라, SockJS 핸드셰이크 입구(`/ws/info`)에
 * `Origin`을 바꿔 가며 실제로 요청해 본다. 프론트 도메인이 막히면 채팅이 통째로 죽으므로
 * **허용돼야 하는 쪽도 같이** 확인한다.
 */
@ActiveProfiles("h2")
@SpringBootTest
@AutoConfigureMockMvc
class WebSocketOriginTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("운영 프론트 도메인은 붙을 수 있다")
    fun testProductionOriginAllowed() {
        mockMvc.perform(get("/ws/info").header(HttpHeaders.ORIGIN, "https://atleti.kr"))
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "https://atleti.kr"))
    }

    @Test
    @DisplayName("www 붙은 도메인도 붙을 수 있다")
    fun testWwwOriginAllowed() {
        mockMvc.perform(get("/ws/info").header(HttpHeaders.ORIGIN, "https://www.atleti.kr"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("로컬 개발 서버도 붙을 수 있다")
    fun testLocalDevOriginAllowed() {
        // Next(3000). 이게 막히면 로컬에서 채팅 개발이 아예 안 된다
        mockMvc.perform(get("/ws/info").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("모르는 사이트는 막힌다 — 이 테스트가 이 작업의 목적이다")
    fun testUnknownOriginRejected() {
        mockMvc.perform(get("/ws/info").header(HttpHeaders.ORIGIN, "https://evil.example.com"))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("도메인이 비슷해 보여도 막힌다")
    fun testLookalikeOriginRejected() {
        // atleti.kr.evil.com 같은 접미사 부착은 흔한 수법이다.
        // 패턴 매칭이 접두사만 보고 통과시키지 않는지 확인한다
        mockMvc.perform(get("/ws/info").header(HttpHeaders.ORIGIN, "https://atleti.kr.evil.com"))
            .andExpect(status().isForbidden)

        // http는 허용 목록에 없다 (로컬 제외)
        mockMvc.perform(get("/ws/info").header(HttpHeaders.ORIGIN, "http://atleti.kr"))
            .andExpect(status().isForbidden)
    }
}
