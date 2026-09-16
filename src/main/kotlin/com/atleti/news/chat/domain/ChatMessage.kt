package com.atleti.news.chat.domain

import java.time.OffsetDateTime
import com.atleti.news.live.domain.LiveMatchState

enum class ChatMessageType {
    USER,
    GOAL,
    GOAL_CANCELLED,

    /**
     * 골 없이 경기 상태만 바뀐 순간 — 킥오프·하프타임·후반 시작·경기 종료.
     */
    STATUS,

    /** 관리자가 보낸 채팅 — 프론트가 다른 색 말풍선으로 그린다 */
    ADMIN,

    /**
     * 전광판 문구. 채팅 줄로는 그리지 않고 LED 보드에만 흘린다.
     */
    LED
}

/**
 * 채팅 메시지 (docs/redis-design.md 6.4).
 */
data class ChatMessage(
    val matchId: String = "",
    val nickname: String = "",
    val content: String = "",
    val sentAt: OffsetDateTime? = null,
    val ipHash: String? = null,
    val type: ChatMessageType = ChatMessageType.USER,
    val live: LiveMatchState? = null
)
