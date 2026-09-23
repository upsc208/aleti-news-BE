package com.atleti.news.chat.api.dto

import com.atleti.news.chat.domain.ChatMessage
import com.atleti.news.live.domain.LiveMatchState

/* 방 정보 응답 — 오픈 여부 + 접속자 수 + 최근 메시지 */
data class ChatRoomResponse(
    val open: Boolean,
    val onlineCount: Long,
    val messages: List<ChatMessage>,
    val live: LiveMatchState? = null
)
