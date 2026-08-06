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

    /** alwaysOpen 스위치를 끈 인스턴스 — 방별 오픈 판정을 실제로 확인하기 위해 */
    private fun strictChatService() = ChatService(
        redis = redis,
        objectMapper = objectMapper,
        upcomingMatchRepository = upcomingMatchRepository,
        matchResultRepository = matchResultRepository,
        slangFilterService = slangFilterService,
        alwaysOpen = false,
        ipSalt = "test-salt"
    )

    @Test
    @DisplayName("자유 채팅방은 CHAT_ALWAYS_OPEN을 꺼도 항상 열려 있다")
    fun testFreeRoomAlwaysOpen() {
        val strict = strictChatService()

        assertEquals(true, strict.isOpen(ChatService.FREE_ROOM_ID))
        // 자유방은 킥오프를 따질 필요가 없으므로 DB를 건드리지 않는다
        verifyNoInteractions(upcomingMatchRepository)

        // 킥오프 정보가 없는 일반 방은 닫혀 있어야 한다 — 자유방만 예외라는 뜻
        assertEquals(false, strict.isOpen("5868012"))
        verify(upcomingMatchRepository, times(1)).findByMatchId("5868012")
    }

    @Test
    @DisplayName("자유 채팅방 메시지는 만료시키지 않고 100건 상한만 적용한다")
    fun testFreeRoomHasNoExpiry() {
        `when`(valOps.increment(anyString())).thenReturn(1L)
        `when`(hashOps.get(anyString(), anyString())).thenReturn(null)
        `when`(hashOps.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true)
        `when`(listOps.leftPush(anyString(), anyString())).thenReturn(1L)

        chatService.send(ChatService.FREE_ROOM_ID, "코케", "다들 안녕", "127.0.0.1")

        val key = "chat:msg:${ChatService.FREE_ROOM_ID}"
        verify(listOps, times(1)).trim(eq(key), eq(0L), eq(99L))   // 상한 100건은 유지
        verify(redis, never()).expire(eq(key), any())              // 메시지 키에는 TTL 없음
        verify(redis, times(1)).persist(eq(key))                   // 남아 있던 TTL 제거
    }

    @Test
    @DisplayName("경기 채팅방 메시지는 24시간 뒤 만료된다")
    fun testMatchRoomKeepsExpiry() {
        `when`(valOps.increment(anyString())).thenReturn(1L)
        `when`(hashOps.get(anyString(), anyString())).thenReturn(null)
        `when`(hashOps.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true)
        `when`(listOps.leftPush(anyString(), anyString())).thenReturn(1L)

        chatService.send("5868012", "코케", "다들 안녕", "127.0.0.1")

        verify(redis, times(1)).expire(eq("chat:msg:5868012"), eq(java.time.Duration.ofHours(24)))
        verify(redis, never()).persist(eq("chat:msg:5868012"))
    }

    @Test
    @DisplayName("닉네임 장부는 자유 채팅방도 24시간 유지된다")
    fun testNicknameLedgerTtl() {
        `when`(valOps.increment(anyString())).thenReturn(1L)
        `when`(hashOps.get(anyString(), anyString())).thenReturn(null)
        `when`(hashOps.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true)
        `when`(listOps.leftPush(anyString(), anyString())).thenReturn(1L)

        // 한때 자유방만 30일이었는데, 한 번 써본 이름이 한 달간 잠기는 문제가 있었다
        chatService.send(ChatService.FREE_ROOM_ID, "코케", "안녕", "10.0.0.1")
        verify(redis, times(1)).expire(eq("chat:nick:free"), eq(java.time.Duration.ofHours(24)))
        verify(redis, times(1)).expire(eq("chat:ipnick:free"), eq(java.time.Duration.ofHours(24)))

        chatService.send("5868012", "코케", "안녕", "10.0.0.1")
        verify(redis, times(1)).expire(eq("chat:nick:5868012"), eq(java.time.Duration.ofHours(24)))
    }

    @Test
    @DisplayName("같은 IP에서 서로 다른 닉네임으로 참여할 수 있다 (공유기·CGNAT)")
    fun testSameIpCanUseDifferentNicknames() {
        `when`(valOps.increment(anyString())).thenReturn(1L)
        `when`(listOps.leftPush(anyString(), anyString())).thenReturn(1L)
        // 이 IP는 이미 "코케"로 장부에 올라 있는 상태
        `when`(hashOps.get(eq("chat:ipnick:free"), eq("10.0.0.1"))).thenReturn("코케")
        `when`(hashOps.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true)

        // 같은 IP의 다른 사람이 다른 닉네임으로 보내도 거절되면 안 된다.
        // 예전에는 "이 방에서는 '코케'로 이미 참여 중이에요"로 막혀,
        // 같은 공유기를 쓰는 사람 전부가 들어오지 못했다.
        chatService.send(ChatService.FREE_ROOM_ID, "그리즈만", "저도 왔어요", "10.0.0.1")

        verify(listOps, times(1)).leftPush(eq("chat:msg:free"), contains("그리즈만"))
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
        // 브라우저 식별자가 없는 구버전 프론트 — 예전처럼 IP가 주인이 된다
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "ip:127.0.0.1")).thenReturn(true)

        val reserved = chatService.reserveNicknamePublic("1", "Koke", "127.0.0.1")
        assertEquals("Koke", reserved)

        // 이미 다른 사람이 선점한 경우
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "ip:10.0.0.2")).thenReturn(false)
        `when`(hashOps.get("chat:nick:1", "koke")).thenReturn("ip:127.0.0.1")

        val conflict = assertFailsWith<ChatException> {
            chatService.reserveNicknamePublic("1", "Koke", "10.0.0.2")
        }
        assertEquals(ChatException.NICKNAME_TAKEN, conflict.code)
    }

    @Test
    @DisplayName("IP가 바뀌어도 같은 브라우저는 자기 닉네임을 다시 쓴다")
    fun testNicknameFollowsBrowserNotIp() {
        // 탭을 닫았다 열거나 모바일에서 재접속하면 공인 IP가 바뀐다.
        // 주인을 IP로 보던 시절에는 여기서 자기 이름에 자기가 잠겼다.
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "cid:browser-1")).thenReturn(false)
        `when`(hashOps.get("chat:nick:1", "koke")).thenReturn("cid:browser-1")

        assertEquals(
            "Koke",
            chatService.reserveNicknamePublic("1", "Koke", "10.0.0.2", "browser-1")
        )
    }

    @Test
    @DisplayName("같은 IP라도 다른 브라우저는 남의 닉네임을 못 가져간다")
    fun testNicknameNotStealableOnSharedIp() {
        // 공유기·카페처럼 IP를 공유하는 곳 — IP가 주인이던 시절엔 그냥 이어받을 수 있었다
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "cid:browser-2")).thenReturn(false)
        `when`(hashOps.get("chat:nick:1", "koke")).thenReturn("cid:browser-1")

        assertFailsWith<ChatException> {
            chatService.reserveNicknamePublic("1", "Koke", "127.0.0.1", "browser-2")
        }
    }

    @Test
    @DisplayName("IP만 저장돼 있던 예전 기록은 같은 IP에서 브라우저 식별자로 승계된다")
    fun testLegacyIpRecordIsClaimedBySameIp() {
        // 이 수정을 배포하는 순간 채팅 중이던 사람들의 기록은 접두사 없는 IP다.
        // 승계가 없으면 그 사람들이 24시간 동안 자기 이름에서 잠긴다.
        `when`(hashOps.putIfAbsent("chat:nick:1", "koke", "cid:browser-1")).thenReturn(false)
        `when`(hashOps.get("chat:nick:1", "koke")).thenReturn("127.0.0.1")

        assertEquals(
            "Koke",
            chatService.reserveNicknamePublic("1", "Koke", "127.0.0.1", "browser-1")
        )
        verify(hashOps).put("chat:nick:1", "koke", "cid:browser-1")
    }
}
