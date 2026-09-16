package com.atleti.news.chat.domain

import com.atleti.news.match.domain.MatchResultRepository
import com.atleti.news.match.domain.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

/** 관리자 채팅 표시와 전광판 문구 (3·4단계) */
class ChatAdminMessageTest {

    private lateinit var redis: StringRedisTemplate
    private lateinit var listOps: ListOperations<String, String>
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var valOps: ValueOperations<String, String>
    private lateinit var objectMapper: ObjectMapper
    private lateinit var chatService: ChatService

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        redis = mock(StringRedisTemplate::class.java)
        listOps = mock(ListOperations::class.java) as ListOperations<String, String>
        hashOps = mock(HashOperations::class.java) as HashOperations<String, String, String>
        valOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redis.opsForList()).thenReturn(listOps)
        `when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
        `when`(redis.opsForValue()).thenReturn(valOps)
        `when`(redis.opsForSet()).thenReturn(mock(org.springframework.data.redis.core.SetOperations::class.java) as org.springframework.data.redis.core.SetOperations<String, String>)
        `when`(hashOps.get(anyString(), anyString())).thenReturn(null)
        `when`(hashOps.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true)
        `when`(listOps.leftPush(anyString(), anyString())).thenReturn(1L)
        `when`(valOps.increment(anyString())).thenReturn(1L)

        objectMapper = ObjectMapper().findAndRegisterModules()
        val slangFilterService = SlangFilterService(PathMatchingResourcePatternResolver(DefaultResourceLoader()))
        slangFilterService.init()

        chatService = ChatService(
            redis = redis,
            objectMapper = objectMapper,
            upcomingMatchRepository = mock(UpcomingMatchRepository::class.java),
            matchResultRepository = mock(MatchResultRepository::class.java),
            slangFilterService = slangFilterService,
            alwaysOpen = true,
            ipSalt = "test-salt",
        )
    }

    @Test
    fun `관리자 메시지는 ADMIN 종류로 나간다`() {
        chatService.send(ChatService.FREE_ROOM_ID, "운영자", "쿠폰 곧 올라간다", "127.0.0.1", admin = true)

        assertThat(published().type).isEqualTo(ChatMessageType.ADMIN)
    }

    @Test
    fun `일반 메시지는 USER 종류 그대로다`() {
        chatService.send(ChatService.FREE_ROOM_ID, "코케", "가자", "127.0.0.1")

        assertThat(published().type).isEqualTo(ChatMessageType.USER)
    }

    @Test
    fun `전광판 문구는 저장하지 않고 발행만 한다`() {
        chatService.sendLedMessage(ChatService.FREE_ROOM_ID, "커피 쿠폰 ABCD1234", "127.0.0.1")

        val message = published()
        assertThat(message.type).isEqualTo(ChatMessageType.LED)
        assertThat(message.content).isEqualTo("커피 쿠폰 ABCD1234")
        // 채팅 기록에 남으면 나중에 들어온 사람 보드에 지난 문구가 다시 뜬다
        verify(listOps, never()).leftPush(anyString(), anyString())
    }

    @Test
    fun `전광판 문구가 비었거나 너무 길면 거절한다`() {
        assertThatThrownBy { chatService.sendLedMessage(ChatService.FREE_ROOM_ID, "   ", "127.0.0.1") }
            .isInstanceOf(ChatException::class.java)
        assertThatThrownBy {
            chatService.sendLedMessage(ChatService.FREE_ROOM_ID, "가".repeat(ChatService.MAX_LED_LENGTH + 1), "127.0.0.1")
        }.isInstanceOf(ChatException::class.java)
    }

    @Test
    fun `관리자는 도배 제한을 받지 않는다`() {
        chatService.send(ChatService.FREE_ROOM_ID, "운영자", "안내 1", "127.0.0.1", admin = true)

        // 제한 카운터를 아예 건드리지 않는다
        verify(valOps, never()).increment("chat:rate:127.0.0.1")
    }

    private fun published(): ChatMessage {
        val json = ArgumentCaptor.forClass(String::class.java)
        verify(redis, times(1)).convertAndSend(eqChannel(), json.capture())
        return objectMapper.readValue(json.value, ChatMessage::class.java)
    }

    private fun eqChannel(): String = org.mockito.Mockito.eq(ChatService.CHANNEL) ?: ChatService.CHANNEL
}
