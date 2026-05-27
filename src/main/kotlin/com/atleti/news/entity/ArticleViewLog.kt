package com.atleti.news.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "article_view_log",
    indexes = [
        Index(name = "idx_article_view_log_article_id_ip", columnList = "article_id, ip_address")
    ]
)
class ArticleViewLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "article_id", nullable = false)
    val articleId: Long,

    @Column(name = "ip_address", nullable = false)
    val ipAddress: String,

    @Column(name = "viewed_at", nullable = false)
    val viewedAt: OffsetDateTime = OffsetDateTime.now()
)
