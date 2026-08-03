package com.atleti.news.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.support.GenericMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import kotlin.test.assertEquals

class ChatPresenceTrackerTest {

    private lateinit var redis: StringRedisTemplate
    private lateinit var valOps: ValueOperations<String, String>
    private lateinit var tracker: ChatPresenceTracker

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        redis = mock(StringRedisTemplate::class.java)
        valOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redis.opsForValue()).thenReturn(valOps)
        tracker = ChatPresenceTracker(redis, ObjectMapper().findAndRegisterModules())
    }

    @Test
    @DisplayName("접속자 수가 바뀌면 방에 있는 사람들에게 즉시 방송된다")
    fun testPresenceIsBroadcastOnChange() {
        val headers = mapOf(
            SimpMessageHeaderAccessor.DESTINATION_HEADER to "/topic/chat/free",
            SimpMessageHeaderAccessor.SESSION_ID_HEADER to "sess-1",
            SimpMessageHeaderAccessor.SUBSCRIPTION_ID_HEADER to "sub-1"
        )
        `when`(valOps.increment("chat:online:free", 1L)).thenReturn(2L)

        tracker.onSubscribe(SessionSubscribeEvent(this, GenericMessage(ByteArray(0), MessageHeaders(headers))))

        // 페이로드에 방과 갱신된 인원이 실려야 프론트가 폴링 없이 숫자를 고칠 수 있다
        val payload = ArgumentCaptor.forClass(String::class.java)
        verify(redis, times(1)).convertAndSend(eq("chat.presence"), payload.capture())
        val event = ObjectMapper().readValue(payload.value, PresenceEvent::class.java)
        assertEquals("free", event.matchId)
        assertEquals(2L, event.onlineCount)
    }

    @Test
    @DisplayName("presence 구독(/topic/presence/...)은 접속자로 세지 않는다")
    fun testPresenceSubscriptionIsNotCounted() {
        val headers = mapOf(
            SimpMessageHeaderAccessor.DESTINATION_HEADER to "/topic/presence/free",
            SimpMessageHeaderAccessor.SESSION_ID_HEADER to "sess-1",
            SimpMessageHeaderAccessor.SUBSCRIPTION_ID_HEADER to "sub-9"
        )

        tracker.onSubscribe(SessionSubscribeEvent(this, GenericMessage(ByteArray(0), MessageHeaders(headers))))

        // 여기서 세면 한 사람이 2명으로 잡힌다
        verifyNoInteractions(valOps)
        verify(redis, never()).convertAndSend(anyString(), any())
    }

    @Test
    @DisplayName("STOMP 구독 이벤트 시 Redis 카운트가 증가한다")
    fun testSubscribeIncrementsOnlineCount() {
        val headers = mapOf(
            SimpMessageHeaderAccessor.DESTINATION_HEADER to "/topic/chat/123",
            SimpMessageHeaderAccessor.SESSION_ID_HEADER to "sess-1",
            SimpMessageHeaderAccessor.SUBSCRIPTION_ID_HEADER to "sub-1"
        )
        val message = GenericMessage(ByteArray(0), MessageHeaders(headers))
        val event = SessionSubscribeEvent(this, message)

        `when`(valOps.increment("chat:online:123", 1L)).thenReturn(1L)

        tracker.onSubscribe(event)

        verify(valOps, times(1)).increment("chat:online:123", 1L)
    }

    @Test
    @DisplayName("STOMP 연결 종료 이벤트 시 구독 중이던 카운트가 감소한다")
    fun testDisconnectDecrementsOnlineCount() {
        // 1. 먼저 구독
        val headers = mapOf(
            SimpMessageHeaderAccessor.DESTINATION_HEADER to "/topic/chat/123",
            SimpMessageHeaderAccessor.SESSION_ID_HEADER to "sess-1",
            SimpMessageHeaderAccessor.SUBSCRIPTION_ID_HEADER to "sub-1"
        )
        val subMessage = GenericMessage(ByteArray(0), MessageHeaders(headers))
        `when`(valOps.increment("chat:online:123", 1L)).thenReturn(1L)
        tracker.onSubscribe(SessionSubscribeEvent(this, subMessage))

        // 2. Disconnect 이벤트
        val disconnectEvent = SessionDisconnectEvent(this, subMessage, "sess-1", CloseStatus.NORMAL)
        `when`(valOps.increment("chat:online:123", -1L)).thenReturn(0L)

        tracker.onDisconnect(disconnectEvent)

        verify(valOps, times(1)).increment("chat:online:123", -1L)
    }

    @Test
    @DisplayName("onlineCount 조회 시 Redis에서 값을 가져온다")
    fun testOnlineCountQuery() {
        `when`(valOps.get("chat:online:123")).thenReturn("5")
        assertEquals(5L, tracker.onlineCount("123"))

        `when`(valOps.get("chat:online:999")).thenReturn(null)
        assertEquals(0L, tracker.onlineCount("999"))
    }
}
