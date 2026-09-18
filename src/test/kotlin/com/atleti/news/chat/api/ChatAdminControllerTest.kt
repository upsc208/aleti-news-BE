package com.atleti.news.chat.api

import com.atleti.news.admin.AdminAuthService
import com.atleti.news.admin.LoginAttempts
import org.springframework.mock.env.MockEnvironment
import com.atleti.news.chat.domain.ChatParticipant
import com.atleti.news.chat.domain.ChatService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class ChatAdminControllerTest {

    private lateinit var chatService: ChatService
    private lateinit var mockMvc: MockMvc

    private val validAdminKey = "test-secret-key"

    /** 계정이 설정돼 있지 않으면 토큰은 항상 거절된다 — 키 검사만 남는다 */
    private val adminAuth = AdminAuthService(MockEnvironment(), object : LoginAttempts {
        override fun fail(ip: String) = 0L
        override fun failures(ip: String) = 0L
        override fun clear(ip: String) {}
    })

    @BeforeEach
    fun setUp() {
        chatService = mock(ChatService::class.java)
        val controller = ChatAdminController(chatService, validAdminKey, adminAuth)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(
                StringHttpMessageConverter(StandardCharsets.UTF_8),
                MappingJackson2HttpMessageConverter()
            )
            .build()
    }

    @Test
    @DisplayName("올바른 X-Admin-Key 헤더로 차단 목록 조회 시 200 OK를 반환한다")
    fun testBannedListSuccess() {
        `when`(chatService.bannedIps()).thenReturn(setOf("1.2.3.4", "5.6.7.8"))

        mockMvc.perform(
            get("/api/admin/chat/ban")
                .header("X-Admin-Key", validAdminKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0]").exists())
            .andExpect(jsonPath("$[1]").exists())
    }

    @Test
    @DisplayName("잘못되었거나 누락된 X-Admin-Key 헤더 사용 시 403 Forbidden을 반환한다")
    fun testBannedListForbidden() {
        mockMvc.perform(
            get("/api/admin/chat/ban")
                .header("X-Admin-Key", "wrong-key")
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/api/admin/chat/ban"))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("올바른 X-Admin-Key 헤더로 IP 차단 등록 시 200 OK를 반환한다")
    fun testBanIpSuccess() {
        val result = mockMvc.perform(
            post("/api/admin/chat/ban")
                .header("X-Admin-Key", validAdminKey)
                .param("ip", "10.0.0.1")
        )
            .andExpect(status().isOk)
            .andReturn()

        assertEquals("차단 완료: 10.0.0.1", result.response.getContentAsString(StandardCharsets.UTF_8))
        verify(chatService).ban("10.0.0.1")
    }

    @Test
    @DisplayName("올바른 X-Admin-Key 헤더로 IP 차단 해제 시 200 OK를 반환한다")
    fun testUnbanIpSuccess() {
        val result = mockMvc.perform(
            delete("/api/admin/chat/ban")
                .header("X-Admin-Key", validAdminKey)
                .param("ip", "10.0.0.1")
        )
            .andExpect(status().isOk)
            .andReturn()

        assertEquals("차단 해제: 10.0.0.1", result.response.getContentAsString(StandardCharsets.UTF_8))
        verify(chatService).unban("10.0.0.1")
    }

    @Test
    @DisplayName("올바른 키로 참가자 목록 조회 시 닉네임·ipHash와 차단용 원본 IP를 함께 반환한다")
    fun testParticipantsSuccess() {
        `when`(chatService.participants("12345")).thenReturn(
            listOf(
                ChatParticipant(nickname = "골레아도르", ip = "1.2.3.4", ipHash = "a3f9", banned = false),
                ChatParticipant(nickname = "도배범", ip = "5.6.7.8", ipHash = "b7c1", banned = true)
            )
        )

        mockMvc.perform(
            get("/api/admin/chat/12345/participants")
                .header("X-Admin-Key", validAdminKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].nickname").value("골레아도르"))
            .andExpect(jsonPath("$[0].ip").value("1.2.3.4"))
            .andExpect(jsonPath("$[0].ipHash").value("a3f9"))
            .andExpect(jsonPath("$[0].banned").value(false))
            .andExpect(jsonPath("$[1].banned").value(true))
    }

    @Test
    @DisplayName("키 없이 참가자 목록을 조회하면 403 — 원본 IP가 새어나가서는 안 된다")
    fun testParticipantsForbidden() {
        mockMvc.perform(get("/api/admin/chat/12345/participants"))
            .andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/admin/chat/12345/participants")
                .header("X-Admin-Key", "wrong-key")
        )
            .andExpect(status().isForbidden)

        verify(chatService, never()).participants(anyString())
    }
}
