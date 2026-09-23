package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 조회수 IP 중복 방지
 */
@Service
class ViewCountService(
    private val redis: StringRedisTemplate,
    private val newsArticleRepository: NewsArticleRepository,
) {
    private val log = LoggerFactory.getLogger(ViewCountService::class.java)

    fun countView(articleId: Long, ip: String): Boolean {
        if (!tryMarkViewed(articleId, ip)) return false
        newsArticleRepository.increaseViewCount(articleId)
        return true
    }

    fun tryMarkViewed(articleId: Long, ip: String): Boolean =
        try {
            redis.opsForValue()
                .setIfAbsent("view:$articleId:$ip", "1", Duration.ofHours(24)) == true
        } catch (e: Exception) {
            log.warn("⚠️ Redis 조회수 마커 실패 — 조회수 증가 생략: {}", e.message)
            false
        }
}
