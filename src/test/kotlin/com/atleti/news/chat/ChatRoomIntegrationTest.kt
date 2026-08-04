package com.atleti.news.chat

import com.atleti.news.service.ChatService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.MimeTypeUtils
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.springframework.web.socket.sockjs.client.SockJsClient
import org.springframework.web.socket.sockjs.client.WebSocketTransport
import java.lang.reflect.Type
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 서로 다른 IP를 가진 두 사람이 같은 방에서 실제로 만나는지 검증하는 통합 테스트.
 *
 * 회귀 방지 대상 — 예전에 각자 자기 메시지만 보이던 버그:
 *   - 프론트가 잘못된 목적지(/sub/chat/room/..., /ws-chat)로 붙어 아무것도 못 받던 문제
 *   - 접속자 수가 입장 시점 값에 멈춰 항상 "1명"으로 보이던 문제
 *
 * 진짜 WebSocket + SockJS + STOMP로 붙는다. 백엔드가 .withSockJS()로 등록했으므로
 * raw WebSocket으로는 접속되지 않는다는 점 자체가 검증 대상이다.
 *
 * Redis가 없으면 통째로 건너뛴다 (로컬: `docker run -p 6379:6379 redis`).
 * CI에는 redis 서비스 컨테이너가 있으므로 실제로 실행된다.
 */
@ActiveProfiles("h2")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatRoomIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var connectionFactory: RedisConnectionFactory
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var rest: TestRestTemplate

    private val opened = mutableListOf<StompSession>()

    private companion object {
        const val FREE = ChatService.FREE_ROOM_ID
        const val MATCH_ROOM = "9999001"      // 킥오프 정보가 없는 방 = 닫힌 방
        const val IP_A = "203.0.113.10"
        const val IP_B = "198.51.100.77"
        const val IP_C = "192.0.2.55"
        const val WAIT_SEC = 10L
    }

    private fun redisAvailable(): Boolean =
        runCatching { connectionFactory.connection.use { it.ping() } }.isSuccess

    @BeforeEach
    fun setUp() {
        assumeTrue(redisAvailable(), "Redis가 없어 채팅 통합 테스트를 건너뜁니다")
        clearChatKeys()
    }

    @AfterEach
    fun tearDown() {
        opened.forEach { runCatching { it.disconnect() } }
        opened.clear()
        if (redisAvailable()) {
            // disconnect는 비동기다. 카운터가 0으로 내려간 걸 확인한 뒤에 키를 지워야
            // 늦게 도착한 감소가 다음 테스트에서 INCR한 값을 갉아먹지 않는다.
            listOf(FREE, MATCH_ROOM).forEach(::awaitDrained)
            clearChatKeys()
        }
    }

    /** 남아 있던 세션의 감소가 모두 반영될 때까지 기다린다 */
    private fun awaitDrained(room: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val v = runCatching { redis.opsForValue().get("chat:online:$room")?.toLongOrNull() }.getOrNull() ?: 0
            if (v <= 0) return
            Thread.sleep(50)
        }
    }

    /**
     * 접속자 수가 기대값이 될 때까지 기다린 뒤 단언한다.
     * STOMP 구독/해제는 서버에서 비동기로 처리되므로 연결 직후 조회하면 아직 0일 수 있다.
     */
    private fun awaitOnlineCount(room: String, expected: Int) {
        val deadline = System.currentTimeMillis() + WAIT_SEC * 1_000
        var actual: Any? = null
        while (System.currentTimeMillis() < deadline) {
            actual = roomInfo(room)["onlineCount"]
            if (actual == expected) return
            Thread.sleep(100)
        }
        assertEquals(expected, actual, "접속자 수가 ${expected}이 되지 않았습니다 (room=$room)")
    }

    private fun clearChatKeys() {
        listOf(FREE, MATCH_ROOM).forEach { room ->
            redis.delete(listOf("chat:msg:$room", "chat:nick:$room", "chat:ipnick:$room", "chat:online:$room"))
        }
        redis.delete(listOf(IP_A, IP_B, IP_C).map { "chat:rl:$it" })
        redis.delete("chat:banned")
    }

    // --- 테스트 참가자 ---

    private inner class Peer(val ip: String) {
        val messages = LinkedBlockingQueue<Map<String, Any?>>()
        val errors = LinkedBlockingQueue<Map<String, Any?>>()
        val presence = LinkedBlockingQueue<Map<String, Any?>>()
        lateinit var session: StompSession

        fun connect(room: String): Peer {
            val stomp = WebSocketStompClient(SockJsClient(listOf(WebSocketTransport(StandardWebSocketClient()))))
            val handshake = WebSocketHttpHeaders().apply { add("X-Forwarded-For", ip) }
            session = stomp
                .connectAsync("ws://localhost:$port/ws", handshake, object : StompSessionHandlerAdapter() {})
                .get(WAIT_SEC, TimeUnit.SECONDS)
            opened += session

            // presence를 방보다 먼저 구독한다 — 순서가 반대면 내 입장 이벤트를 놓친다
            subscribe("/topic/presence/$room", presence)
            subscribe("/topic/chat/$room", messages)
            subscribe("/user/queue/errors", errors)
            return this
        }

        private fun subscribe(destination: String, sink: LinkedBlockingQueue<Map<String, Any?>>) {
            session.subscribe(destination, object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java
                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    val bytes = payload as? ByteArray ?: return
                    runCatching {
                        sink += objectMapper.readValue(bytes, object : TypeReference<Map<String, Any?>>() {})
                    }
                }
            })
        }

        fun send(room: String, nickname: String, content: String) {
            val headers = StompHeaders().apply {
                destination = "/app/chat/$room"
                contentType = MimeTypeUtils.APPLICATION_JSON
            }
            session.send(headers, objectMapper.writeValueAsBytes(mapOf("nickname" to nickname, "content" to content)))
        }

        fun reserveNickname(room: String, nickname: String): HttpStatus {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                add("X-Forwarded-For", ip)
            }
            val body = objectMapper.writeValueAsString(mapOf("nickname" to nickname))
            val res = rest.exchange(
                "/api/chat/$room/nickname", HttpMethod.POST, HttpEntity(body, headers), String::class.java
            )
            return HttpStatus.valueOf(res.statusCode.value())
        }
    }

    private fun LinkedBlockingQueue<Map<String, Any?>>.next(): Map<String, Any?>? =
        poll(WAIT_SEC, TimeUnit.SECONDS)

    private fun roomInfo(room: String): Map<String, Any?> {
        val body = rest.getForObject("/api/chat/$room", String::class.java)
        return objectMapper.readValue(body, object : TypeReference<Map<String, Any?>>() {})
    }

    // --- 테스트 ---

    @Test
    @DisplayName("서로 다른 IP의 두 사람이 같은 방에서 메시지를 주고받는다")
    fun twoPeersExchangeMessages() {
        val a = Peer(IP_A).connect(FREE)
        val b = Peer(IP_B).connect(FREE)

        a.send(FREE, "코케", "다들 안녕하세요")

        // 핵심: 보낸 사람이 아니라 "상대"에게 도착해야 한다
        val gotByB = b.messages.next()
        assertNotNull(gotByB, "B가 A의 메시지를 받지 못했습니다 (errors=${b.errors})")
        assertEquals("다들 안녕하세요", gotByB["content"])
        assertEquals("코케", gotByB["nickname"])

        // 서버가 검증·저장한 뒤 되돌려주므로 보낸 사람에게도 도착한다
        assertEquals("다들 안녕하세요", a.messages.next()?.get("content"))

        b.send(FREE, "그리즈만", "Aupa Atleti")
        assertEquals("Aupa Atleti", a.messages.next()?.get("content"))
    }

    @Test
    @DisplayName("접속자 수가 실시간으로 밀려오고, 퇴장 시 줄어든다")
    fun onlineCountIsPushedInRealTime() {
        val a = Peer(IP_A).connect(FREE)
        assertEquals(1, a.presence.next()?.get("onlineCount"), "본인 입장 이벤트를 못 받았습니다")

        // 두 번째 사람이 들어오면 "이미 방에 있던" A에게 즉시 알림이 가야 한다.
        // 이게 없으면 A의 화면은 계속 1명에 멈춘다 (예전 버그).
        val b = Peer(IP_B).connect(FREE)
        assertEquals(2, a.presence.next()?.get("onlineCount"))

        b.session.disconnect()
        opened.remove(b.session)
        assertEquals(1, a.presence.next()?.get("onlineCount"), "퇴장이 반영되지 않았습니다")
    }

    @Test
    @DisplayName("presence 구독은 접속자로 세지 않는다")
    fun presenceSubscriptionIsNotCountedAsParticipant() {
        // 한 사람이 방 1 + presence 1 + 에러 1 = 3개를 구독하지만 인원은 1명이어야 한다
        val a = Peer(IP_A).connect(FREE)

        // 내 입장 이벤트를 먼저 기다린다 — 구독이 서버에 반영된 시점을 잡기 위해
        assertEquals(1, a.presence.next()?.get("onlineCount"))
        awaitOnlineCount(FREE, 1)
    }

    @Test
    @DisplayName("자유 채팅방은 항상 열려 있고, 킥오프 정보가 없는 경기방은 닫혀 있다")
    fun freeRoomIsAlwaysOpenButMatchRoomIsNot() {
        assertEquals(true, roomInfo(FREE)["open"])
        assertEquals(false, roomInfo(MATCH_ROOM)["open"])
    }

    @Test
    @DisplayName("닫힌 방으로 보내면 방 전체가 아니라 보낸 사람에게만 사유가 온다")
    fun closedRoomRejectsSenderOnly() {
        val a = Peer(IP_A).connect(MATCH_ROOM)
        val b = Peer(IP_B).connect(MATCH_ROOM)

        a.send(MATCH_ROOM, "코케", "여기 열렸나요")

        val error = a.errors.next()
        assertNotNull(error, "거절 사유를 받지 못했습니다")
        assertTrue(
            (error["error"] as? String)?.contains("열려 있지 않") == true,
            "예상과 다른 사유: $error"
        )
        assertNull(b.messages.poll(2, TimeUnit.SECONDS), "닫힌 방인데 메시지가 전달됐습니다")
    }

    @Test
    @DisplayName("닉네임은 방 안에서 선착순이고, 다른 IP가 같은 닉네임을 못 쓴다")
    fun nicknameIsReservedPerRoom() {
        assertEquals(HttpStatus.OK, Peer(IP_A).reserveNickname(FREE, "코케"))
        assertEquals(HttpStatus.OK, Peer(IP_B).reserveNickname(FREE, "그리즈만"))
        assertEquals(HttpStatus.CONFLICT, Peer(IP_C).reserveNickname(FREE, "코케"))
    }

    @Test
    @DisplayName("같은 IP의 여러 사람이 각자 다른 닉네임으로 들어온다 (공유기·CGNAT)")
    fun samePublicIpCanHostMultiplePeople() {
        // 집 공유기 하나에 폰과 PC가 붙으면 서버에는 같은 공인 IP로 보인다.
        // 예전에는 "IP당 닉네임 1개" 제한 때문에 먼저 들어온 사람이 나머지를 전부 막았다.
        val phone = Peer(IP_A)
        val laptop = Peer(IP_A)

        assertEquals(HttpStatus.OK, phone.reserveNickname(FREE, "코케"))
        assertEquals(HttpStatus.OK, laptop.reserveNickname(FREE, "그리즈만"),
            "같은 IP의 두 번째 사람이 막혔습니다")

        phone.connect(FREE)
        laptop.connect(FREE)
        phone.send(FREE, "코케", "폰에서 보냅니다")

        val got = laptop.messages.next()
        assertNotNull(got, "같은 IP의 다른 사람에게 메시지가 가지 않았습니다 (errors=${laptop.errors})")
        assertEquals("폰에서 보냅니다", got["content"])
    }

    @Test
    @DisplayName("메시지에 ipHash만 실리고 원본 IP는 나가지 않는다")
    fun rawIpIsNeverExposed() {
        val a = Peer(IP_A).connect(FREE)
        val b = Peer(IP_B).connect(FREE)

        a.send(FREE, "코케", "hello")
        b.send(FREE, "그리즈만", "hola")

        val seen = listOf(a.messages.next(), a.messages.next()).filterNotNull()
        assertEquals(2, seen.size, "메시지 2건을 받지 못했습니다")

        val hashes = seen.mapNotNull { it["ipHash"] as? String }.toSet()
        assertEquals(2, hashes.size, "두 사람의 ipHash가 같습니다: $hashes")
        assertTrue(hashes.all { it.length == 4 }, "ipHash는 4자리여야 합니다: $hashes")

        val dump = objectMapper.writeValueAsString(seen)
        assertTrue(!dump.contains(IP_A) && !dump.contains(IP_B), "원본 IP가 노출됐습니다: $dump")
    }

    @Test
    @DisplayName("자유 채팅방 기록은 서버에 남고 만료되지 않는다")
    fun freeRoomHistoryIsServerSideAndNeverExpires() {
        val a = Peer(IP_A).connect(FREE)
        a.send(FREE, "코케", "첫 번째")
        a.messages.next()
        a.send(FREE, "코케", "두 번째")
        a.messages.next()

        // 새로 들어온 사람이 이전 대화를 볼 수 있어야 한다 (브라우저 저장이 아니라 서버 보관)
        @Suppress("UNCHECKED_CAST")
        val stored = roomInfo(FREE)["messages"] as List<Map<String, Any?>>
        assertEquals(2, stored.size)
        assertEquals("첫 번째", stored[0]["content"], "오래된 것부터 정렬되어야 합니다")

        // TTL 없음(-1) — 하루 조용해도 자유 채팅방 기록은 사라지지 않는다
        assertEquals(-1L, redis.getExpire("chat:msg:$FREE"))
    }
}
