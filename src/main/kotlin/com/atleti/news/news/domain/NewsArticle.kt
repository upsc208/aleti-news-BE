package com.atleti.news.news.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

enum class ArticleType { WEB_ARTICLE, SNS_POST, YOUTUBE }

@Entity
@Table(
    name = "news_article",
    indexes = [Index(name = "idx_news_article_original_url", columnList = "original_url", unique = true)]
)
class NewsArticle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "article_type", nullable = false)
    val articleType: ArticleType = ArticleType.WEB_ARTICLE,

    @Column(name = "source_name", nullable = false, columnDefinition = "TEXT")
    val sourceName: String,

    @Column(name = "author_name", columnDefinition = "TEXT")
    val authorName: String? = null, // 트위터 핸들 또는 기사 작성자

    @Column(name = "original_url", nullable = false, unique = true, columnDefinition = "TEXT")
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

    @Column(name = "category")
    val category: String? = "ETC", // [이적시장], [경기리뷰], [오피셜], [부상/명단], [인터뷰], [예상라인업], [기타]

    @Column(name = "is_atm_related", nullable = false)
    val isAtmRelated: Boolean = false,

    @Column(name = "view_count", nullable = false)
    var viewCount: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
