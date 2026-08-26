package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 조회수 IP 중복 방지 (docs/redis-design.md Phase 1).
 * 기존 article_view_log 테이블(EXISTS + INSERT + 새벽 청소 배치)을
 * Redis SETNX + 24h TTL 한 번으로 대체한다.
 */
@Service
class ViewCountService(private val redis: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(ViewCountService::class.java)

    /**
     * 이 IP가 이 기사를 24시간 내 처음 보는지 원자적으로 판정.
     * true = 처음 봄 (조회수 올려야 함) / false = 이미 봄 또는 Redis 장애.
     * Redis 장애 시 조회수만 안 오르고 기사 조회는 계속돼야 하므로 false로 fail-open.
     */
    fun tryMarkViewed(articleId: Long, ip: String): Boolean =
        try {
            redis.opsForValue()
                .setIfAbsent("view:$articleId:$ip", "1", Duration.ofHours(24)) == true
        } catch (e: Exception) {
            log.warn("⚠️ Redis 조회수 마커 실패 — 조회수 증가 생략: {}", e.message)
            false
        }
}
