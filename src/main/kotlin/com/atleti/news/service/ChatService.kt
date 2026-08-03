package com.atleti.news.service

import com.atleti.news.dto.ChatMessage
import com.atleti.news.repository.MatchResultRepository
import com.atleti.news.repository.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** 채팅 정책 위반 (닫힌 방, 도배, 금지된 내용 등) — 보낸 사람에게만 에러로 전달된다 */
class ChatException(message: String) : RuntimeException(message)

/**
 * 관리자 화면 전용 참가자 정보.
 * 채팅 화면에는 ipHash 4자리만 보이므로 도배하는 사람을 봐도 차단할 IP를 알 수 없다.
 * 그 연결 고리를 관리자에게만 제공한다 — 원본 IP는 이 응답(관리자 키 필요)에만 실린다.
 */
data class ChatParticipant(
    val nickname: String,
    val ip: String,
    val ipHash: String,
    val banned: Boolean
)

/**
 * 채팅 발행/조회 (docs/redis-design.md 6.2, 6.6, 6.8).
 * 메시지 1건 발행 시 순서:
 *   1) 방 오픈 검증  — 킥오프 1시간 전 ~ 킥오프 +3시간만 허용
 *   2) 내용 검증     — 닉네임 2~12자, 본문 200자 이하, URL 금지
 *   3) rate limit    — IP당 10초에 5건
 *   4) 저장          — LPUSH + LTRIM(최근 100개) + EXPIRE 24h
 *   5) PUBLISH       — 모든 앱 인스턴스에 방송
 */
@Service
class ChatService(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    private val matchResultRepository: MatchResultRepository,
    private val slangFilterService: SlangFilterService,
    @Value("\${atleti.chat.always-open}") private val alwaysOpen: Boolean,
    @Value("\${atleti.chat.ip-salt}") private val ipSalt: String
) {
    companion object {
        const val CHANNEL = "chat.messages"   // Pub/Sub 채널 (단일 채널, 페이로드에 matchId 포함)

        /**
         * 상시 개방 자유 채팅방의 방 키.
         * 경기 방 키는 FotMob 숫자 ID이므로 문자열 "free"와 충돌하지 않는다.
         */
        const val FREE_ROOM_ID = "free"

        private const val BANNED_KEY = "chat:banned"
        private const val MAX_RECENT = 100L
        private const val MAX_CONTENT_LENGTH = 200
        private const val RATE_LIMIT_WINDOW = 10L   // 초
        private const val RATE_LIMIT_MAX = 5        // 창 내 최대 전송 수
        private val URL_PATTERN = Regex("(?i)(https?://|www\\.)")

        private val ROOM_TTL: Duration = Duration.ofHours(24)

        /**
         * 자유 채팅방 닉네임 장부 TTL.
         * 메시지와 달리 장부는 상한이 없어 무기한 두면 방문자 수만큼 계속 커진다.
         * 활동할 때마다 갱신되므로 계속 쓰는 사람은 닉네임을 잃지 않는다.
         */
        private val FREE_NICK_TTL: Duration = Duration.ofDays(30)

        fun isFreeRoom(matchId: String) = matchId == FREE_ROOM_ID
    }

    fun send(matchId: String, nickname: String, content: String, ip: String) {
        requireRoomOpen(matchId)
        requireNotBanned(ip)
        val cleanNickname = validateNickname(nickname)
        val cleanContent = validateContent(content)
        checkRateLimit(ip)
        val fixedNickname = reserveNickname(matchId, cleanNickname, ip)

        val message = ChatMessage(
            matchId = matchId,
            nickname = fixedNickname,
            content = cleanContent,
            sentAt = OffsetDateTime.now(),
            ipHash = ipHash(ip)
        )
        val json = objectMapper.writeValueAsString(message)
        val key = "chat:msg:$matchId"

        val size = redis.opsForList().leftPush(key, json)
        redis.opsForList().trim(key, 0, MAX_RECENT - 1)
        if (isFreeRoom(matchId)) {
            // 자유 채팅방은 만료시키지 않는다 — 하루 조용했다고 대화가 통째로 사라지면
            // "항상 열려 있는 방"이 고장난 것처럼 보인다. 크기는 위 LTRIM이 100건으로 고정하므로
            // TTL이 없어도 늘어나지 않는다 (최대 ~70KB).
            // 첫 메시지일 때만 혹시 남아 있을 수 있는 예전 TTL을 떼어낸다.
            if (size == 1L) redis.persist(key)
        } else {
            redis.expire(key, ROOM_TTL)
        }
        redis.convertAndSend(CHANNEL, json)
    }

    /** REST API용 닉네임 사전 선점/검증 */
    fun reserveNicknamePublic(matchId: String, nickname: String, ip: String): String {
        requireRoomOpen(matchId)
        requireNotBanned(ip)
        val cleanNickname = validateNickname(nickname)
        return reserveNickname(matchId, cleanNickname, ip)
    }

    /**
     * 입장 시 보여줄 최근 메시지 — 오래된 것부터. 닫힌 방도 TTL 내 열람은 허용 (6.6).
     *
     * Redis가 없으면 빈 목록을 돌려준다 (fail-open, redis-design 5).
     * 예전에는 예외가 그대로 올라가 `GET /api/chat/{matchId}`가 500을 냈다 —
     * 상시 개방인 자유 채팅방은 항상 열려 있는 진입점이라 페이지 자체가 깨진다.
     */
    fun recentMessages(matchId: String): List<ChatMessage> =
        try {
            (redis.opsForList().range("chat:msg:$matchId", 0, MAX_RECENT - 1) ?: emptyList())
                .mapNotNull { runCatching { objectMapper.readValue(it, ChatMessage::class.java) }.getOrNull() }
                .reversed()
        } catch (e: Exception) {
            println("⚠️ [Chat] 최근 메시지 조회 실패 (matchId=$matchId): ${e.message}")
            emptyList()
        }

    // --- 방 오픈 판정 (6.6) ---

    fun isOpen(matchId: String): Boolean {
        if (isFreeRoom(matchId)) return true   // 자유 채팅방은 킥오프와 무관하게 상시 개방
        if (alwaysOpen) return true
        val kickoff = findKickoffTime(matchId) ?: return false
        val now = OffsetDateTime.now()
        return !now.isBefore(kickoff.minusHours(1)) && !now.isAfter(kickoff.plusHours(3))
    }

    private fun requireRoomOpen(matchId: String) {
        if (!isOpen(matchId)) throw ChatException("채팅방이 열려 있지 않습니다. 경기 시작 1시간 전에 열려요.")
    }

    /** 킥오프 시각: 예정 경기 우선, 없으면 경기 결과에서. 둘 다 없으면 방 없음 취급 */
    private fun findKickoffTime(matchId: String): OffsetDateTime? {
        val dateStr = upcomingMatchRepository.findByMatchId(matchId)?.matchDate
            ?: matchResultRepository.findByMatchId(matchId)?.matchDate
            ?: return null
        return runCatching { OffsetDateTime.parse(dateStr) }
            .recoverCatching {
                Instant.parse(dateStr.replace(" ", "T"))
                    .atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
            }
            .getOrNull()
    }

    // --- 내용 검증 (6.8) ---

    private fun validateNickname(nickname: String): String {
        val clean = nickname.trim()
        if (clean.length !in 2..12) throw ChatException("닉네임은 2~12자로 입력해주세요.")
        slangFilterService.validateText(clean, "닉네임")
        return clean
    }

    private fun validateContent(content: String): String {
        val clean = content.trim()
        if (clean.isEmpty()) throw ChatException("빈 메시지는 보낼 수 없습니다.")
        if (clean.length > MAX_CONTENT_LENGTH) throw ChatException("메시지는 ${MAX_CONTENT_LENGTH}자 이하로 입력해주세요.")
        if (URL_PATTERN.containsMatchIn(clean)) throw ChatException("링크는 보낼 수 없습니다.")
        slangFilterService.validateText(clean, "메시지")
        return clean
    }

    // --- IP 차단 (6.8): 관리자가 등록한 IP는 전송 거부. Set, TTL 없음(영구) ---

    private fun requireNotBanned(ip: String) {
        if (redis.opsForSet().isMember(BANNED_KEY, ip) == true) {
            throw ChatException("관리자에 의해 차단된 상태예요.")
        }
    }

    fun ban(ip: String) { redis.opsForSet().add(BANNED_KEY, ip) }
    fun unban(ip: String) { redis.opsForSet().remove(BANNED_KEY, ip) }
    fun bannedIps(): Set<String> = redis.opsForSet().members(BANNED_KEY) ?: emptySet()

    /**
     * 이 방에서 닉네임을 정한 사람 목록 (관리자 전용).
     * 출처는 닉네임 장부 chat:ipnick:{matchId} (IP → 표시닉네임)이므로,
     * 지금 접속 중인 사람이 아니라 **이 방에 한 번이라도 참여한 사람**이 나온다.
     * 나간 사람도 남지만 차단 대상을 찾는 용도에는 그편이 오히려 유용하다 (방 TTL 24h와 함께 소멸).
     */
    fun participants(matchId: String): List<ChatParticipant> {
        val entries = redis.opsForHash<String, String>().entries("chat:ipnick:$matchId")
        val banned = bannedIps()
        return entries.map { (ip, nickname) ->
            ChatParticipant(
                nickname = nickname,
                ip = ip,
                ipHash = ipHash(ip),
                banned = ip in banned
            )
        }.sortedBy { it.nickname }
    }

    // --- ipHash (6.4): 동일인 식별용. salt 포함 SHA-256 앞 4자리, 원본 IP는 노출 금지 ---

    private fun ipHash(ip: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((ipSalt + ip).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)

    // --- 닉네임 정책 (6.5): 방 단위 선착순 선점 + IP당 1개 고정 ---

    /**
     * 두 장부를 유지한다 (둘 다 방 TTL 24h와 함께 소멸):
     *   chat:nick:{matchId}   정규화닉네임 → IP   : 선착순 선점 (남의 닉네임 사용 차단)
     *   chat:ipnick:{matchId} IP → 표시닉네임     : IP당 1개 고정 (닉네임 갈아타기 차단)
     * 반환값은 이 방에서 확정된 닉네임 — 재접속 시 입력이 달라도 원래 닉네임으로 고정.
     */
    private fun reserveNickname(matchId: String, nickname: String, ip: String): String {
        val nickKey = "chat:nick:$matchId"
        val ipKey = "chat:ipnick:$matchId"
        val hash = redis.opsForHash<String, String>()

        // 이미 이 방에서 닉네임을 정한 IP인가?
        val myNickname = hash.get(ipKey, ip)
        if (myNickname != null) {
            if (normalize(myNickname) != normalize(nickname)) {
                throw ChatException("이 방에서는 '${myNickname}'(으)로 이미 참여 중이에요. 닉네임은 바꿀 수 없어요.")
            }
            // 활동 중이면 장부 수명을 늘려준다 — 자유 채팅방에서 잠시 안 왔다고
            // 쓰던 닉네임을 남에게 뺏기지 않도록.
            touchNicknameLedgers(matchId, nickKey, ipKey)
            return myNickname
        }

        // 처음 정하는 IP — 선점 시도 (HSETNX: 원자적이라 동시 시도에도 한 명만 성공)
        val normalized = normalize(nickname)
        val reserved = hash.putIfAbsent(nickKey, normalized, ip)
        if (!reserved && hash.get(nickKey, normalized) != ip) {
            throw ChatException("이미 사용 중인 닉네임이에요. 다른 닉네임을 입력해주세요.")
        }
        hash.put(ipKey, ip, nickname)
        touchNicknameLedgers(matchId, nickKey, ipKey)
        return nickname
    }

    /** 닉네임 장부 수명 — 경기 방은 방과 함께 24h, 자유 채팅방은 30일 */
    private fun touchNicknameLedgers(matchId: String, nickKey: String, ipKey: String) {
        val ttl = if (isFreeRoom(matchId)) FREE_NICK_TTL else ROOM_TTL
        redis.expire(nickKey, ttl)
        redis.expire(ipKey, ttl)
    }

    /** 유사 닉네임 사칭 방지용 정규화: trim → 소문자 → 연속 공백 축약 ("Koke" ≒ "koke ") */
    private fun normalize(nickname: String): String =
        nickname.trim().lowercase().replace(Regex("\\s+"), " ")

    // --- 도배 방지 (6.8): INCR 원자 카운터 + TTL 창 ---

    private fun checkRateLimit(ip: String) {
        val key = "chat:rl:$ip"
        val count = redis.opsForValue().increment(key) ?: return
        if (count == 1L) redis.expire(key, Duration.ofSeconds(RATE_LIMIT_WINDOW))
        if (count > RATE_LIMIT_MAX) throw ChatException("메시지를 너무 자주 보내고 있어요. 잠시 후 다시 시도해주세요.")
    }
}
