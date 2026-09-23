package com.atleti.news.news.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.OffsetDateTime

/* 관리자가 지운 기사의 원문 주소*/
@Entity
@Table(
    name = "deleted_article",
    indexes = [Index(name = "idx_deleted_article_url", columnList = "original_url", unique = true)]
)
class DeletedArticle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "original_url", nullable = false, unique = true, columnDefinition = "TEXT")
    val originalUrl: String,

    @Column(name = "title_ko", columnDefinition = "TEXT")
    val titleKo: String? = null,

    @Column(name = "deleted_at", nullable = false, updatable = false)
    val deletedAt: OffsetDateTime = OffsetDateTime.now()
)
