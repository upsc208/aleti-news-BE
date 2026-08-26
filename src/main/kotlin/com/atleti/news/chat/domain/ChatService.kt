package com.atleti.news.chat.domain

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
 *
 * [code]는 프론트가 사유별로 다르게 반응하기 위한 것이다. 특히 [NICKNAME_TAKEN]을 받으면
 * 닉네임 입력 모달을 다시 열어야 한다 — 문구만 보여주면 사용자는 이름을 바꿀 방법이 없어
 * 채팅이 영영 막힌다.
 */
class ChatException(message: String, val code: String = REJECTED) : RuntimeException(message) {
    companion object {
        const val REJECTED = "REJECTED"
        const val NICKNAME_TAKEN = "NICKNAME_TAKEN"
    }
}

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

        /**
         * 골 알림을 보내는 봇 이름 (live-match-feed-design 6.7-2).
         * 유저가 선점하면 가짜 골 알림을 칠 수 있으므로 예약어로 막는다.
         */
        const val BOT_NICKNAME = "매치봇"


        // chat:nick 값의 접두사 — 주인이 브라우저 식별자인지 IP인지 구분한다.
        // 접두사가 없는 값은 IP만 저장하던 옛 기록이다.
        private const val CLIENT_OWNER_PREFIX = "cid:"
        private const val IP_OWNER_PREFIX = "ip:"

        private const val MAX_RECENT = 100L
        private const val MAX_CONTENT_LENGTH = 200
        private const val RATE_LIMIT_MAX = 5        // 창 내 최대 전송 수
        private val URL_PATTERN = Regex("(?i)(https?://|www\\.)")

        fun isFreeRoom(matchId: String) = matchId == FREE_ROOM_ID
    }

    fun send(matchId: String, nickname: String, content: String, ip: String, clientId: String? = null) {
        requireRoomOpen(matchId)
        requireNotBanned(ip)
        val cleanNickname = validateNickname(nickname)
        val cleanContent = validateContent(content)
        checkRateLimit(ip)
        val fixedNickname = reserveNickname(matchId, cleanNickname, ip, clientId)

        val message = ChatMessage(
            matchId = matchId,
            nickname = fixedNickname,
            content = cleanContent,
            sentAt = OffsetDateTime.now(),
            ipHash = ipHash(ip)
        )
        store(matchId, message)
    }

    /**
     * 라이브 골 알림 발행 (live-match-feed-design 5.1).
     *
     * 유저 검증(방 오픈·금칙어·rate limit)을 거치지 않는 유일한 경로라 호출자는
     * [LiveMatchPoller] 하나뿐이다. 밖에서 들어온 값이 이리로 흘러들지 않도록,
     * 클라이언트가 보낸 [ChatMessage]의 `type`·`live`는 [send]에서 통째로 버려진다.
     *
     * 저장 경로가 유저 메시지와 같으므로 골 알림도 `chat:msg` 목록에 남는다 —
     * 늦게 들어온 사람의 대화 내역에서 골 라인만 빠져 있으면 오히려 고장으로 보인다.
     */
    fun publishLiveMessage(matchId: String, type: ChatMessageType, content: String, live: LiveMatchState?) {
        store(
            matchId,
            ChatMessage(
                matchId = matchId,
                nickname = BOT_NICKNAME,
                content = content,
                sentAt = OffsetDateTime.now(),
                ipHash = null,          // 봇은 사람이 아니다 (설계 6.7-4)
                type = type,
                live = live
            )
        )
    }

    /**
     * 골 없이 경기 상태만 바뀐 순간을 알린다 — 킥오프·하프타임·후반 시작·경기 종료.
     *
     * [publishLiveMessage]와 달리 **`chat:msg` 목록에 저장하지 않는다.** 이건 대화가
     * 아니라 스코어보드 갱신이라, 남겨두면 늦게 들어온 사람의 대화 내역이 빈 줄로 채워진다.
     * 프론트는 `type != USER`인 메시지를 채팅 라인으로 그리지 않고 `live`만 꺼내 쓰므로
     * (useChatRoom.ts의 isHumanMessage) 화면에는 스코어보드와 시계만 조용히 바뀐다.
     *
     * 저장하지 않는 대가로 **놓치면 끝이다.** 그래도 괜찮은 이유는 다음 상태 변화나
     * 골에서 다시 실려 나가고, 입장 시 [liveState]가 어차피 최신 스냅샷을 주기 때문이다.
     */
    fun publishLiveState(matchId: String, live: LiveMatchState) {
        val message = ChatMessage(
            matchId = matchId,
            nickname = BOT_NICKNAME,
            content = "",           // 채팅 라인으로 그려지지 않는다 — 나르는 건 live 하나뿐
            sentAt = OffsetDateTime.now(),
            ipHash = null,
            type = ChatMessageType.STATUS,
            live = live
        )
        redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message))
    }

    /** 최근 메시지 목록에 넣고 모든 인스턴스로 방송한다 */
    private fun store(matchId: String, message: ChatMessage) {
        val json = objectMapper.writeValueAsString(message)
        val key = ChatKeys.messages(matchId)

        val size = redis.opsForList().leftPush(key, json)
        redis.opsForList().trim(key, 0, MAX_RECENT - 1)
        if (isFreeRoom(matchId)) {
            // 자유 채팅방은 만료시키지 않는다 — 하루 조용했다고 대화가 통째로 사라지면
            // "항상 열려 있는 방"이 고장난 것처럼 보인다. 크기는 위 LTRIM이 100건으로 고정하므로
            // TTL이 없어도 늘어나지 않는다 (최대 ~70KB).
            // 첫 메시지일 때만 혹시 남아 있을 수 있는 예전 TTL을 떼어낸다.
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
            println("⚠️ [Live] 스냅샷 조회 실패 (matchId=$matchId): ${e.message}")
            null
        }
    }

    /**
     * REST API용 닉네임 사전 선점/검증.
     * 이미 내가 가진 이름을 다시 부르면 그대로 통과한다 (멱등) — 프론트는 저장해둔 닉네임으로
     * 재입장할 때마다 이걸 호출해서 "아직 내 이름이 맞는지"를 확인한다.
     */
    fun reserveNicknamePublic(matchId: String, nickname: String, ip: String, clientId: String? = null): String {
        requireRoomOpen(matchId)
        requireNotBanned(ip)
        val cleanNickname = validateNickname(nickname)
        return reserveNickname(matchId, cleanNickname, ip, clientId)
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
            (redis.opsForList().range(ChatKeys.messages(matchId), 0, MAX_RECENT - 1) ?: emptyList())
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
        return MatchKickoff.parse(dateStr)
    }

    // --- 내용 검증 (6.8) ---

    private fun validateNickname(nickname: String): String {
        val clean = nickname.trim()
        if (clean.length !in 2..12) throw ChatException("닉네임은 2~12자로 입력해주세요.")
        if (isReserved(clean)) {
            // NICKNAME_TAKEN이어야 프론트가 입력 모달을 다시 연다. REJECTED면 문구만 뜨고
            // 이름을 바꿀 방법이 없어 채팅이 막힌다
            throw ChatException("사용할 수 없는 닉네임이에요.", ChatException.NICKNAME_TAKEN)
        }
        slangFilterService.validateText(clean, "닉네임")
        return clean
    }

    /**
     * 봇 사칭 차단 (live-match-feed-design 6.7-2).
     * 공백을 끼워 넣는 우회("매 치 봇")까지 같은 이름으로 본다 — 채팅 화면에서는
     * 구분이 안 가므로 가짜 골 알림에 그대로 쓰인다.
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

    // --- IP 차단 (6.8): 관리자가 등록한 IP는 전송 거부. Set, TTL 없음(영구) ---

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
     * 출처는 닉네임 장부 chat:ipnick:{matchId} (IP → 표시닉네임)이므로,
     * 지금 접속 중인 사람이 아니라 **이 방에 한 번이라도 참여한 사람**이 나온다.
     * 나간 사람도 남지만 차단 대상을 찾는 용도에는 그편이 오히려 유용하다 (방 TTL 24h와 함께 소멸).
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

    // --- ipHash (6.4): 동일인 식별용. salt 포함 SHA-256 앞 4자리, 원본 IP는 노출 금지 ---

    private fun ipHash(ip: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((ipSalt + ip).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)

    // --- 닉네임 정책 (6.5): 방 단위 선착순 선점 ---

    /**
     * 두 장부를 유지한다 (둘 다 방 TTL 24h와 함께 소멸):
     *   chat:nick:{matchId}   정규화닉네임 → 주인 : 선착순 선점 (남의 닉네임 사용 차단)
     *   chat:ipnick:{matchId} IP → 최근 표시닉네임 : 관리자 참가자 목록용 (차단할 IP 찾기)
     *
     * **주인은 IP가 아니라 브라우저 식별자다** ([ChatClient]). IP를 주인으로 쓰던 시절에는
     * 모바일·재접속처럼 IP가 바뀌는 상황에서 자기가 쓰던 이름에 자기가 잠겼고,
     * 프론트는 저장된 닉네임이 있으면 입력 모달을 건너뛰므로 이름을 바꿀 방법도 없이
     * 채팅이 영영 막혔다. 반대로 공유기·카페·CGNAT처럼 여러 사람이 한 IP를 쓰는 곳에서는
     * 남이 내 이름을 그냥 이어받을 수도 있었다.
     *
     * "IP당 닉네임 1개" 제한은 두지 않는다 — 같은 이유(공유 IP)로 먼저 들어온 사람이
     * 나머지 전부를 막아버린다. 닉네임 갈아타기는 아래 것들이 이미 막고 있다:
     *   - 닉네임 선착순 선점 (남의 이름 사칭 불가)
     *   - IP당 rate limit (이름을 바꿔도 전송량 상한은 그대로)
     *   - 메시지에 실리는 ipHash (이름을 바꿔도 같은 사람인 게 화면에 보인다)
     */
    private fun reserveNickname(matchId: String, nickname: String, ip: String, clientId: String?): String {
        val nickKey = ChatKeys.nicknames(matchId)
        val ipKey = ChatKeys.ipNicknames(matchId)
        val hash = redis.opsForHash<String, String>()

        // 선점 시도 (HSETNX: 원자적이라 동시 시도에도 한 명만 성공).
        val normalized = normalize(nickname)
        val owner = ownerToken(clientId, ip)
        if (!hash.putIfAbsent(nickKey, normalized, owner)) {
            val current = hash.get(nickKey, normalized)
            if (current != owner) {
                // 예전 IP 기준 기록은 같은 IP에서 올 때 한 번 이어받는다.
                // 이게 없으면 이번 배포 시점에 채팅 중이던 사람들이 24시간 동안
                // 자기 이름에서 잠긴 채로 남는다.
                if (!isLegacyIpOwner(current, ip)) {
                    throw ChatException(
                        "이미 사용 중인 닉네임이에요. 다른 닉네임을 입력해주세요.",
                        ChatException.NICKNAME_TAKEN
                    )
                }
                hash.put(nickKey, normalized, owner)
            }
        }
        // 관리자 참가자 목록용 — 도배하는 사람의 차단할 IP를 찾는 통로다
        hash.put(ipKey, ip, nickname)
        touchNicknameLedgers(nickKey, ipKey)
        return nickname
    }

    /** 브라우저 식별자가 있으면 그것이 주인, 없으면(구버전 프론트) 예전처럼 IP */
    private fun ownerToken(clientId: String?, ip: String): String =
        if (clientId.isNullOrBlank()) "$IP_OWNER_PREFIX$ip" else "$CLIENT_OWNER_PREFIX$clientId"

    /** 접두사 없이 IP만 들어 있던 옛 형식(그리고 같은 IP의 새 형식)인지 */
    private fun isLegacyIpOwner(current: String?, ip: String): Boolean =
        current != null && ip != ClientIp.UNKNOWN && (current == ip || current == "$IP_OWNER_PREFIX$ip")

    /**
     * 닉네임 장부 수명 — 자유 채팅방도 경기 방과 같이 24h.
     * 한때 자유방만 30일로 늘렸었는데(잠시 안 와도 이름을 지키도록),
     * IP당 1개 제한과 맞물려 "한 번 써본 이름이 한 달간 잠기는" 상태가 됐다.
     * 제한을 없앤 지금은 늘려둘 이유가 없고, 짧아야 안 쓰는 이름이 제때 풀린다.
     */
    private fun touchNicknameLedgers(nickKey: String, ipKey: String) {
        redis.expire(nickKey, ChatKeys.ROOM_TTL)
        redis.expire(ipKey, ChatKeys.ROOM_TTL)
    }

    /** 유사 닉네임 사칭 방지용 정규화: trim → 소문자 → 연속 공백 축약 ("Koke" ≒ "koke ") */
    private fun normalize(nickname: String): String =
        nickname.trim().lowercase().replace(Regex("\\s+"), " ")

    // --- 도배 방지 (6.8): INCR 원자 카운터 + TTL 창 ---

    private fun checkRateLimit(ip: String) {
        val key = ChatKeys.rateLimit(ip)
        val count = redis.opsForValue().increment(key) ?: return
        if (count == 1L) redis.expire(key, ChatKeys.RATE_LIMIT_WINDOW)
        if (count > RATE_LIMIT_MAX) throw ChatException("메시지를 너무 자주 보내고 있어요. 잠시 후 다시 시도해주세요.")
    }
}
