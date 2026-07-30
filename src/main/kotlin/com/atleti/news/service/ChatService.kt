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
    @Value("\${atleti.chat.always-open}") private val alwaysOpen: Boolean
) {
    companion object {
        const val CHANNEL = "chat.messages"   // Pub/Sub 채널 (단일 채널, 페이로드에 matchId 포함)
        private const val MAX_RECENT = 100L
        private const val MAX_CONTENT_LENGTH = 200
        private const val RATE_LIMIT_WINDOW = 10L   // 초
        private const val RATE_LIMIT_MAX = 5        // 창 내 최대 전송 수
        private val URL_PATTERN = Regex("(?i)(https?://|www\\.)")
    }

    fun send(matchId: String, nickname: String, content: String, ip: String) {
        requireRoomOpen(matchId)
        val cleanNickname = validateNickname(nickname)
        val cleanContent = validateContent(content)
        checkRateLimit(ip)

        val message = ChatMessage(
            matchId = matchId,
            nickname = cleanNickname,
            content = cleanContent,
            sentAt = OffsetDateTime.now()
        )
        val json = objectMapper.writeValueAsString(message)
        val key = "chat:msg:$matchId"

        redis.opsForList().leftPush(key, json)
        redis.opsForList().trim(key, 0, MAX_RECENT - 1)
        redis.expire(key, Duration.ofHours(24))
        redis.convertAndSend(CHANNEL, json)
    }

    /** 입장 시 보여줄 최근 메시지 — 오래된 것부터. 닫힌 방도 TTL 내 열람은 허용 (6.6) */
    fun recentMessages(matchId: String): List<ChatMessage> =
        (redis.opsForList().range("chat:msg:$matchId", 0, MAX_RECENT - 1) ?: emptyList())
            .map { objectMapper.readValue(it, ChatMessage::class.java) }
            .reversed()

    // --- 방 오픈 판정 (6.6) ---

    fun isOpen(matchId: String): Boolean {
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
        return clean
    }

    private fun validateContent(content: String): String {
        val clean = content.trim()
        if (clean.isEmpty()) throw ChatException("빈 메시지는 보낼 수 없습니다.")
        if (clean.length > MAX_CONTENT_LENGTH) throw ChatException("메시지는 ${MAX_CONTENT_LENGTH}자 이하로 입력해주세요.")
        if (URL_PATTERN.containsMatchIn(clean)) throw ChatException("링크는 보낼 수 없습니다.")
        return clean
    }

    // --- 도배 방지 (6.8): INCR 원자 카운터 + TTL 창 ---

    private fun checkRateLimit(ip: String) {
        val key = "chat:rl:$ip"
        val count = redis.opsForValue().increment(key) ?: return
        if (count == 1L) redis.expire(key, Duration.ofSeconds(RATE_LIMIT_WINDOW))
        if (count > RATE_LIMIT_MAX) throw ChatException("메시지를 너무 자주 보내고 있어요. 잠시 후 다시 시도해주세요.")
    }
}
