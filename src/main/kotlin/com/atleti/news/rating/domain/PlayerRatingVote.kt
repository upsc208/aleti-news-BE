package com.atleti.news.rating.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "player_rating_vote",
    uniqueConstraints = [
        // 한 IP는 한 경기의 한 선수에 대해 한 표만 (재투표 시 update)
        UniqueConstraint(name = "uk_player_rating_vote", columnNames = ["match_id", "player_name", "ip_address"])
    ],
    indexes = [Index(name = "idx_player_rating_vote_match", columnList = "match_id")]
)
class PlayerRatingVote(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "match_id", nullable = false)
    val matchId: String,

    @Column(name = "player_name", nullable = false)
    val playerName: String,

    @Column(name = "shirt")
    val shirt: String? = null,

    @Column(name = "ip_address", nullable = false)
    val ipAddress: String,

    // 0~5점
    @Column(name = "score", nullable = false)
    var score: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
