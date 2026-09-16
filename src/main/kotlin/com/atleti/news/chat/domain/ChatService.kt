package com.atleti.news.chat.domain

import org.slf4j.LoggerFactory
import com.atleti.news.chat.infra.ChatClient
import com.atleti.news.common.ClientIp
import com.atleti.news.live.domain.LiveMatchState
import com.atleti.news.match.domain.MatchResultRepository
import com.atleti.news.match.domain.UpcomingMatchRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import com.atleti.news.live.domain.LiveMatchKeys
import com.atleti.news.match.domain.MatchKickoff

/**
 * 채팅 정책 위반 (닫힌 방, 도배, 금지된 내용 등) — 보낸 사람에게만 에러로 전달된다.
 */
class ChatException(message: String, val code: String = REJECTED) : RuntimeException(message) {
    companion object {
        const val REJECTED = "REJECTED"
        const val NICKNAME_TAKEN = "NICKNAME_TAKEN"
    }
}

/**
 * 관리자 화면 전용 참가자 정보.
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
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    companion object {
        const val CHANNEL = "chat.messages"   // Pub/Sub 채널 (단일 채널, 페이로드에 matchId 포함)

        /**
         * 상시 개방 자유 채팅방의 방 키.
         */
        const val FREE_ROOM_ID = "free"

        /**
         * 골 알림을 보내는 봇 이름 (live-match-feed-design 6.7-2).
         */
        const val BOT_NICKNAME = "매치봇"


        private const val CLIENT_OWNER_PREFIX = "cid:"
        private const val IP_OWNER_PREFIX = "ip:"

        private const val MAX_RECENT = 100L
        private const val MAX_CONTENT_LENGTH = 200

        const val MAX_LED_LENGTH = 40
        /**
         * [ChatKeys.RATE_LIMIT_WINDOW](10초) 안에 한 IP가 보낼 수 있는 최대 건수.
         */
        private const val RATE_LIMIT_MAX = 10
        private val URL_PATTERN = Regex("(?i)(https?://|www\\.)")

        fun isFreeRoom(matchId: String) = matchId == FREE_ROOM_ID
    }

    fun send(matchId: String, nickname: String, content: String, ip: String, clientId: String? = null, admin: Boolean = false) {
        requireRoomOpen(matchId)
        requireNotBanned(ip)
        val cleanNickname = validateNickname(nickname, admin)
        val cleanContent = validateContent(content)
        if (!admin) checkRateLimit(ip)
        val fixedNickname = reserveNickname(matchId, cleanNickname, ip, clientId)

        val message = ChatMessage(
            matchId = matchId,
            nickname = fixedNickname,
            content = cleanContent,
            sentAt = OffsetDateTime.now(),
            ipHash = ipHash(ip),
            type = if (admin) ChatMessageType.ADMIN else ChatMessageType.USER,
        )
        store(matchId, message)
    }

    /**
     * 라이브 골 알림 발행 (live-match-feed-design 5.1).
     */
    fun publishLiveMessage(matchId: String, type: ChatMessageType, content: String, live: LiveMatchState?) {
        store(
            matchId,
            ChatMessage(
                matchId = matchId,
                nickname = BOT_NICKNAME,
                content = content,
                sentAt = OffsetDateTime.now(),
                ipHash = null,
                type = type,
                live = live
            )
        )
    }

    /**
     * 골 없이 경기 상태만 바뀐 순간을 알린다 — 킥오프·하프타임·후반 시작·경기 종료.
     */
    fun publishLiveState(matchId: String, live: LiveMatchState) {
        val message = ChatMessage(
            matchId = matchId,
            nickname = BOT_NICKNAME,
            content = "",
            sentAt = OffsetDateTime.now(),
            ipHash = null,
            type = ChatMessageType.STATUS,
            live = live
        )
        redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message))
    }

    /** 최근 메시지 목록에 넣고 모든 인스턴스로 방송한다 */
    /**
     * 전광판 문구 발행 (관리자 전용).
     */
    fun sendLedMessage(matchId: String, text: String, ip: String) {
        requireRoomOpen(matchId)
        val clean = text.trim()
        if (clean.isEmpty()) throw ChatException("전광판에 올릴 내용을 입력해주세요.")
        if (clean.length > MAX_LED_LENGTH) throw ChatException("전광판 문구는 ${MAX_LED_LENGTH}자 이하로 입력해주세요.")

        val message = ChatMessage(
            matchId = matchId,
            nickname = BOT_NICKNAME,
            content = clean,
            sentAt = OffsetDateTime.now(),
            type = ChatMessageType.LED,
        )
        redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message))
    }

    private fun store(matchId: String, message: ChatMessage) {
        val json = objectMapper.writeValueAsString(message)
        val key = ChatKeys.messages(matchId)

        val size = redis.opsForList().leftPush(key, json)
        redis.opsForList().trim(key, 0, MAX_RECENT - 1)
        if (isFreeRoom(matchId)) {
            if (size == 1L) redis.persist(key)
        } else {
            redis.expire(key, ChatKeys.ROOM_TTL)
        }
        redis.convertAndSend(CHANNEL, json)
    }

    /**
     * 입장 시 내려줄 라이브 스냅샷 (live-match-feed-design 6.2).
     *
     * 폴러가 5초마다 갱신해 둔 것을 **읽기만** 한다. 여기서 네이버를 직접 부르면
     * 하프타임에 200명이 새로고침하는 순간 그대로 200번 호출이 된다 (설계 5.3).
     * 자유 채팅방이거나 아직 매핑·폴링 전이면 null.
     */
    fun liveState(matchId: String): LiveMatchState? {
        if (isFreeRoom(matchId)) return null
        return try {
            redis.opsForValue().get(LiveMatchKeys.state(matchId))
                ?.let { objectMapper.readValue(it, LiveMatchState::class.java) }
        } catch (e: Exception) {
            log.warn("⚠️ [Live] 스냅샷 조회 실패 (matchId=$matchId): ${e.message}", e)
            null
        }
    }

    /**
     * REST API용 닉네임 사전 선점/검증.
     */
    fun reserveNicknamePublic(matchId: String, nickname: String, ip: String, clientId: String? = null): String {
        requireRoomOpen(matchId)
        requireNotBanned(ip)
        val cleanNickname = validateNickname(nickname)
        return reserveNickname(matchId, cleanNickname, ip, clientId)
    }

    /**
     * 입장 시 보여줄 최근 메시지 — 오래된 것부터. 닫힌 방도 TTL 내 열람은 허용 (6.6).
     */
    fun recentMessages(matchId: String): List<ChatMessage> =
        try {
            (redis.opsForList().range(ChatKeys.messages(matchId), 0, MAX_RECENT - 1) ?: emptyList())
                .mapNotNull { runCatching { objectMapper.readValue(it, ChatMessage::class.java) }.getOrNull() }
                .reversed()
        } catch (e: Exception) {
            log.warn("⚠️ [Chat] 최근 메시지 조회 실패 (matchId=$matchId): ${e.message}", e)
            emptyList()
        }

    // --- 방 오픈 판정---

    fun isOpen(matchId: String): Boolean {
        if (isFreeRoom(matchId)) return true
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
        return MatchKickoff.parse(dateStr)
    }

    private fun validateNickname(nickname: String, admin: Boolean = false): String {
        val clean = nickname.trim()
        if (clean.length !in 2..12) throw ChatException("닉네임은 2~12자로 입력해주세요.")
        if (isReserved(clean)) {
            throw ChatException("사용할 수 없는 닉네임이에요.", ChatException.NICKNAME_TAKEN)
        }
        if (!admin) slangFilterService.validateText(clean, "닉네임")
        return clean
    }

    /**
     * 봇 사칭 차단 (live-match-feed-design 6.7-2).
     */
    private fun isReserved(nickname: String): Boolean =
        nickname.replace(Regex("\\s"), "").lowercase() == BOT_NICKNAME

    private fun validateContent(content: String): String {
        val clean = content.trim()
        if (clean.isEmpty()) throw ChatException("빈 메시지는 보낼 수 없습니다.")
        if (clean.length > MAX_CONTENT_LENGTH) throw ChatException("메시지는 ${MAX_CONTENT_LENGTH}자 이하로 입력해주세요.")
        if (URL_PATTERN.containsMatchIn(clean)) throw ChatException("링크는 보낼 수 없습니다.")
        slangFilterService.validateText(clean, "메시지")
        return clean
    }

    private fun requireNotBanned(ip: String) {
        if (redis.opsForSet().isMember(ChatKeys.BANNED, ip) == true) {
            throw ChatException("관리자에 의해 차단된 상태예요.")
        }
    }

    fun ban(ip: String) { redis.opsForSet().add(ChatKeys.BANNED, ip) }
    fun unban(ip: String) { redis.opsForSet().remove(ChatKeys.BANNED, ip) }
    fun bannedIps(): Set<String> = redis.opsForSet().members(ChatKeys.BANNED) ?: emptySet()

    /**
     * 이 방에서 닉네임을 정한 사람 목록 (관리자 전용).
     */
    fun participants(matchId: String): List<ChatParticipant> {
        val entries = redis.opsForHash<String, String>().entries(ChatKeys.ipNicknames(matchId))
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


    private fun ipHash(ip: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((ipSalt + ip).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)

    /**
     * 두 장부를 유지한다 (둘 다 방 TTL 24h와 함께 소멸):
     *   chat:nick:{matchId}   정규화닉네임 → 주인 : 선착순 선점 (남의 닉네임 사용 차단)
     *   chat:ipnick:{matchId} IP → 최근 표시닉네임 : 관리자 참가자 목록용 (차단할 IP 찾기)
     */
    private fun reserveNickname(matchId: String, nickname: String, ip: String, clientId: String?): String {
        val nickKey = ChatKeys.nicknames(matchId)
        val ipKey = ChatKeys.ipNicknames(matchId)
        val hash = redis.opsForHash<String, String>()

        val normalized = normalize(nickname)
        val owner = ownerToken(clientId, ip)
        if (!hash.putIfAbsent(nickKey, normalized, owner)) {
            val current = hash.get(nickKey, normalized)
            if (current != owner) {
                if (!isLegacyIpOwner(current, ip)) {
                    throw ChatException(
                        "이미 사용 중인 닉네임이에요. 다른 닉네임을 입력해주세요.",
                        ChatException.NICKNAME_TAKEN
                    )
                }
                hash.put(nickKey, normalized, owner)
            }
        }
        hash.put(ipKey, ip, nickname)
        touchNicknameLedgers(nickKey, ipKey)
        return nickname
    }

    private fun ownerToken(clientId: String?, ip: String): String =
        if (clientId.isNullOrBlank()) "$IP_OWNER_PREFIX$ip" else "$CLIENT_OWNER_PREFIX$clientId"

    private fun isLegacyIpOwner(current: String?, ip: String): Boolean =
        current != null && ip != ClientIp.UNKNOWN && (current == ip || current == "$IP_OWNER_PREFIX$ip")

    /**
     * 닉네임 장부 수명 — 자유 채팅방도 경기 방과 같이 24h.
     */
    private fun touchNicknameLedgers(nickKey: String, ipKey: String) {
        redis.expire(nickKey, ChatKeys.ROOM_TTL)
        redis.expire(ipKey, ChatKeys.ROOM_TTL)
    }

    private fun normalize(nickname: String): String =
        nickname.trim().lowercase().replace(Regex("\\s+"), " ")


    private fun checkRateLimit(ip: String) {
        val key = ChatKeys.rateLimit(ip)
        val count = redis.opsForValue().increment(key) ?: return
        if (count == 1L) redis.expire(key, ChatKeys.RATE_LIMIT_WINDOW)
        if (count > RATE_LIMIT_MAX) throw ChatException("메시지를 너무 자주 보내고 있어요. 잠시 후 다시 시도해주세요.")
    }
}
