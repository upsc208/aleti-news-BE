package com.atleti.news.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "news_article",
    indexes = [Index(name = "idx_news_article_original_url", columnList = "original_url", unique = true)]
)
class NewsArticle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "source_name", nullable = false)
    val sourceName: String,

    @Column(name = "original_url", nullable = false, unique = true)
    val originalUrl: String,

    @Column(name = "title_ko", columnDefinition = "TEXT")
    val titleKo: String?,

    @Column(name = "summary_ko", columnDefinition = "TEXT")
    val summaryKo: String?,

    @Column(name = "content_ko", columnDefinition = "TEXT")
    val contentKo: String?, // 번역 전문

    @Column(name = "content_raw", columnDefinition = "TEXT")
    val contentRaw: String?, // 원문 보존용

    @Column(name = "image_url", columnDefinition = "TEXT")
    val imageUrl: String?,

    @Column(name = "published_at")
    val publishedAt: OffsetDateTime?,

    @Column(name = "is_atm_related", nullable = false)
    val isAtmRelated: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
