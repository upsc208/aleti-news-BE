package com.atleti.news.chat.domain

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 키 문자열을 **바이트 단위로** 고정한다.
 *
 * Redis 키는 배포와 함께 바뀌지 않는다. 운영 중인 서버에 이미 그 이름으로 데이터가 들어 있고,
 * 키 이름을 한 글자만 바꿔도 **에러 없이 그냥 빈 값이 돌아온다** — 진행 중인 경기의 채팅이
 * 통째로 사라지는데 로그에는 아무것도 안 남는다.
 *
 * [ChatKeys]로 리터럴을 모으는 리팩토링이 그 사고를 낼 수 있는 대표적인 작업이라,
 * 이전에 쓰던 문자열을 여기에 그대로 적어 둔다. 앞으로 키를 바꾸려면 이 테스트를 먼저
 * 고쳐야 하고, 그때 "운영 데이터는 어떻게 옮길 것인가"를 자연히 마주하게 된다.
 *
 * 채팅 통합 테스트는 Redis가 없으면 통째로 건너뛴다(`assumeTrue`). 이 테스트는 Redis 없이도
 * 돌아야 키 이름이 상시 보호된다.
 */
class ChatKeysTest {

    private val matchId = "5868022"

    @Test
    @DisplayName("경기 방 키 이름은 바뀌지 않는다")
    fun testRoomKeyFormats() {
        assertEquals("chat:msg:5868022", ChatKeys.messages(matchId))
        assertEquals("chat:nick:5868022", ChatKeys.nicknames(matchId))
        assertEquals("chat:ipnick:5868022", ChatKeys.ipNicknames(matchId))
        assertEquals("chat:online:5868022", ChatKeys.online(matchId))
    }

    @Test
    @DisplayName("자유 채팅방도 같은 규칙을 탄다")
    fun testFreeRoomKeyFormats() {
        // 자유 채팅방은 matchId 자리에 "free"가 들어갈 뿐 키 모양은 같다
        assertEquals("chat:msg:free", ChatKeys.messages(ChatService.FREE_ROOM_ID))
        assertEquals("chat:online:free", ChatKeys.online(ChatService.FREE_ROOM_ID))
    }

    @Test
    @DisplayName("방과 무관한 키 — rate limit은 IP별, 차단은 전역")
    fun testGlobalKeyFormats() {
        assertEquals("chat:rl:203.0.113.7", ChatKeys.rateLimit("203.0.113.7"))
        assertEquals("chat:banned", ChatKeys.BANNED)
    }

    @Test
    @DisplayName("TTL 값도 고정한다")
    fun testTtls() {
        // 방 TTL이 짧아지면 경기 도중 대화가 사라지고, 길어지면 안 쓰는 닉네임이 안 풀린다
        assertEquals(Duration.ofHours(24), ChatKeys.ROOM_TTL)
        // 도배 판정 창. 이 값이 바뀌면 전송 상한의 의미가 통째로 달라진다
        assertEquals(Duration.ofSeconds(10), ChatKeys.RATE_LIMIT_WINDOW)
    }
}
