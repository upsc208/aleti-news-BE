package com.atleti.news.dto

/**
 * 채팅 메시지 (Phase 2a — 최소 형태).
 * Phase 2b~2c에서 matchId, sentAt, ipHash가 추가될 예정 (docs/redis-design.md 6.4).
 */
data class ChatMessage(
    val nickname: String,
    val content: String
)
