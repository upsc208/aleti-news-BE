package com.atleti.news.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "upcoming_match")
class UpcomingMatchEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val opponent: String,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String,

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
