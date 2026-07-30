package com.atleti.news.dto

import java.time.OffsetDateTime

/**
 * 채팅 메시지 (docs/redis-design.md 6.4).
 * 클라이언트는 nickname/content만 보내고, matchId/sentAt은 서버가 채운다.
 * Redis List에 저장되는 형태 = 브로드캐스트되는 형태 (같은 JSON).
 * ipHash는 Phase 2c에서 추가 예정.
 */
data class ChatMessage(
    val matchId: String = "",
    val nickname: String = "",
    val content: String = "",
    val sentAt: OffsetDateTime? = null
)
