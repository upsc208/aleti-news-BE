package com.atleti.news.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "collection_log")
class CollectionLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: CollectionStatus,

    @Column(name = "scanned_count")
    val scannedCount: Int = 0,

    @Column(name = "ai_processed_count")
    val aiProcessedCount: Int = 0,

    @Column(name = "twitter_api_call_count")
    val twitterApiCallCount: Int = 0,

    @Column(name = "error_msg", columnDefinition = "TEXT")
    val errorMsg: String? = null,

    @Column(name = "execution_time_ms")
    val executionTimeMs: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)

enum class CollectionStatus {
    SUCCESS, FAIL
}
