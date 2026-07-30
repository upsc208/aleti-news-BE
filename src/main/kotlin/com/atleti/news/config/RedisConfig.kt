package com.atleti.news.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory

@Configuration
class RedisConfig {
    private val log = LoggerFactory.getLogger(RedisConfig::class.java)

    /**
     * 기동 시 Redis 연결을 1회 확인한다.
     * 연결 실패해도 앱은 정상 기동한다 — Redis는 부가 기능(조회수 마커)이므로
     * 본 기능(기사 조회)을 막으면 안 된다 (fail-open, docs/redis-design.md 5).
     */
    @Bean
    fun redisConnectionCheck(factory: RedisConnectionFactory) = ApplicationRunner {
        try {
            factory.connection.use { it.ping() }
            log.info("✅ Redis 연결 확인 완료")
        } catch (e: Exception) {
            log.warn("⚠️ Redis 연결 실패 — 조회수 중복 방지 없이 기동합니다: {}", e.message)
        }
    }
}
