package com.atleti.news.controller

import com.atleti.news.service.ChatService
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

    @BeforeEach
    fun setUp() {
        chatService = mock(ChatService::class.java)
        val controller = ChatAdminController(chatService, validAdminKey)
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
}
