package com.atleti.news.dto

import java.time.OffsetDateTime

/**
 * 메시지 종류 (docs/live-match-feed-design.md 6.1).
 * 프론트가 말풍선 스타일을 나누는 기준이다. 기본값이 [USER]라 이 필드가 없던
 * 기존 저장분·클라이언트 전송분이 그대로 동작한다.
 */
enum class ChatMessageType { USER, GOAL, GOAL_CANCELLED }

/**
 * 채팅 메시지 (docs/redis-design.md 6.4).
 * 클라이언트는 nickname/content만 보내고, 나머지는 서버가 채운다.
 * Redis List에 저장되는 형태 = 브로드캐스트되는 형태 (같은 JSON).
 * ipHash: salt 포함 해시 앞 4자리 — 동일인 식별용. 원본 IP는 절대 내보내지 않는다.
 *
 * 골 알림([ChatMessageType.GOAL] 계열)은 [live]에 스코어 스냅샷을 함께 싣는다.
 * 메시지 하나가 「채팅 라인 추가」와 「스코어보드 갱신」을 동시에 처리하므로
 * 프론트는 스코어보드를 위해 별도 요청을 하지 않는다 (live-match-feed-design 6장).
 */
data class ChatMessage(
    val matchId: String = "",
    val nickname: String = "",
    val content: String = "",
    val sentAt: OffsetDateTime? = null,
    /** 봇 메시지에서는 null — 프론트가 동일인 식별에 쓴다면 null 체크가 필요하다 */
    val ipHash: String? = null,
    val type: ChatMessageType = ChatMessageType.USER,
    /** GOAL 계열에만 실린다. 유저 메시지에서는 null */
    val live: LiveMatchState? = null
)
