package com.atleti.news.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "upcoming_match",
    uniqueConstraints = [UniqueConstraint(columnNames = ["opponent", "matchDate"])]
)
class UpcomingMatchEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val opponent: String,
    
    @Column(nullable = false)
    val matchDate: String,
    
    val isHome: Boolean,
    val tournament: String,

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
