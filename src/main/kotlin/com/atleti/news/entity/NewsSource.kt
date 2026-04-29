package com.atleti.news.entity

import jakarta.persistence.*

@Entity
@Table(name = "news_source")
class NewsSource(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "internal_id", nullable = false, unique = true)
    val internalId: String, // "MatteoMoretto", "Marca" 등 매칭용

    @Column(name = "display_name", nullable = false)
    val displayName: String, // "마테오 모레토", "마르카"

    @Column(name = "source_url")
    val sourceUrl: String? = null,

    @Column(name = "icon_url")
    val iconUrl: String? = null,

    @Column(name = "category")
    val category: String, // "JOURNALIST", "OFFICIAL", "MEDIA"

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
)
