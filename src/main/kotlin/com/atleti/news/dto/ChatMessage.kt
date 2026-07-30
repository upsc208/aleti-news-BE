package com.atleti.news.dto

import java.time.OffsetDateTime

/**
 * 채팅 메시지 (docs/redis-design.md 6.4).
 * 클라이언트는 nickname/content만 보내고, 나머지는 서버가 채운다.
 * Redis List에 저장되는 형태 = 브로드캐스트되는 형태 (같은 JSON).
 * ipHash: salt 포함 해시 앞 4자리 — 동일인 식별용. 원본 IP는 절대 내보내지 않는다.
 */
data class ChatMessage(
    val matchId: String = "",
    val nickname: String = "",
    val content: String = "",
    val sentAt: OffsetDateTime? = null,
    val ipHash: String? = null
)
