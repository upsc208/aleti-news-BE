package com.atleti.news.entity

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.time.ZoneId

@Entity
@Table(name = "news_headline")
class NewsHeadline(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 여러 최신 뉴스를 종합해 만든 헤드라인 한 줄 (순수 텍스트)
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
)
