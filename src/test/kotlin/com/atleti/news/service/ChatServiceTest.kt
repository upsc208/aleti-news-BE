package com.atleti.news.service

import com.atleti.news.repository.MatchResultRepository
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatServiceTest {

    private lateinit var redis: StringRedisTemplate
    private lateinit var objectMapper: ObjectMapper
    private lateinit var upcomingMatchRepository: UpcomingMatchRepository
    private lateinit var matchResultRepository: MatchResultRepository
    private lateinit var slangFilterService: SlangFilterService
    private lateinit var chatService: ChatService

    private lateinit var listOps: ListOperations<String, String>
    private lateinit var setOps: SetOperations<String, String>
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var valOps: ValueOperations<String, String>

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        redis = mock(StringRedisTemplate::class.java)
        objectMapper = ObjectMapper().findAndRegisterModules()
        upcomingMatchRepository = mock(UpcomingMatchRepository::class.java)
        matchResultRepository = mock(MatchResultRepository::class.java)

        val resolver = PathMatchingResourcePatternResolver(DefaultResourceLoader())
        slangFilterService = SlangFilterService(resolver)
        slangFilterService.init()

        listOps = mock(ListOperations::class.java) as ListOperations<String, String>
        setOps = mock(SetOperations::class.java) as SetOperations<String, String>
        hashOps = mock(HashOperations::class.java) as HashOperations<String, String, String>
        valOps = mock(ValueOperations::class.java) as ValueOperations<String, String>

        `when`(redis.opsForList()).thenReturn(listOps)
        `when`(redis.opsForSet()).thenReturn(setOps)
        `when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
        `when`(redis.opsForValue()).thenReturn(valOps)

        chatService = ChatService(
            redis = redis,
            objectMapper = objectMapper,
            upcomingMatchRepository = upcomingMatchRepository,
            matchResultRepository = matchResultRepository,
            slangFilterService = slangFilterService,
            alwaysOpen = true,
            ipSalt = "test-salt"
        )
    }

    @Test
    @DisplayName("정상 메시지 전송 시 Redis 저장 및 PUBLISH가 수행된다")
    fun testSendSuccess() {
        `when`(valOps.increment(anyString())).thenReturn(1L)
        `when`(hashOps.get(anyString(), anyString())).thenReturn(null)
        `when`(hashOps.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true)

        chatService.send("1", "그리즈만", "오늘 라인업 좋네요!", "127.0.0.1")

        verify(listOps, times(1)).leftPush(eq("chat:msg:1"), anyString())
        verify(redis, times(1)).convertAndSend(eq("chat.messages"), anyString())
    }

    @Test
    @DisplayName("금칙어 닉네임 전송 시 ChatException이 발생한다")
    fun testSendProfanityNickname() {
        assertFailsWith<ChatException> {
            chatService.send("1", "관리자", "안녕하세요", "127.0.0.1")
        }
    }

    @Test
    @DisplayName("금칙어 내용 전송 시 ChatException이 발생한다")
    fun testSendProfanityContent() {
        assertFailsWith<ChatException> {
            chatService.send("1", "테스터", "씨발", "127.0.0.1")
        }
    }

    @Test
    @DisplayName("차단된 IP에서 전송 시 ChatException이 발생한다")
    fun testBannedIpSend() {
        `when`(setOps.isMember("chat:banned", "1.2.3.4")).thenReturn(true)

        assertFailsWith<ChatException> {
            chatService.send("1", "테스터", "안녕하세요", "1.2.3.4")
        }
    }

    @Test
    @DisplayName("IP 차단 및 차단 해증 기능 동작 검증")
    fun testBanAndUnban() {
        chatService.ban("10.0.0.1")
        verify(setOps).add("chat:banned", "10.0.0.1")

        chatService.unban("10.0.0.1")
        verify(setOps).remove("chat:banned", "10.0.0.1")
    }

    @Test
    @DisplayName("닉네임 사전 선점 REST 서비스 성공 및 중복 차단")
    fun testReserveNicknamePublic() {
        `when`(hashOps.get("chat:ipnick:1", "127.0.0.1")).thenReturn(null)
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "127.0.0.1")).thenReturn(true)

        val reserved = chatService.reserveNicknamePublic("1", "Koke", "127.0.0.1")
        assertEquals("Koke", reserved)

        // 이미 다른 IP가 선점한 경우
        `when`(hashOps.get("chat:ipnick:1", "10.0.0.2")).thenReturn(null)
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "10.0.0.2")).thenReturn(false)
        `when`(hashOps.get("chat:nick:1", "koke")).thenReturn("127.0.0.1")

        assertFailsWith<ChatException> {
            chatService.reserveNicknamePublic("1", "Koke", "10.0.0.2")
        }
    }
}
