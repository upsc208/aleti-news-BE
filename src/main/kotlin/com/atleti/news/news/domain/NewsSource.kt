package com.atleti.news.news.domain

import jakarta.persistence.*

@Entity
@Table(name = "news_source")
class NewsSource(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "internal_id", nullable = false, unique = true, columnDefinition = "TEXT")
    val internalId: String, // "MatteoMoretto", "Marca" 등 매칭용

    @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
    val displayName: String, // "마테오 모레토", "마르카"

    @Column(name = "source_url", columnDefinition = "TEXT")
    val sourceUrl: String? = null,

    @Column(name = "icon_url", columnDefinition = "TEXT")
    val iconUrl: String? = null,

    @Column(name = "category", columnDefinition = "TEXT")
    val category: String, // "JOURNALIST", "OFFICIAL", "MEDIA"

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
)
