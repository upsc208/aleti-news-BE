package com.atleti.news.admin

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/* 로그인 실패 횟수를 IP별로 카운트. if Redis가 죽으면 잠금만 포기하고 로그인은 통과*/
@Component
class RedisLoginAttempts(private val redis: StringRedisTemplate) : LoginAttempts {
    private val log = LoggerFactory.getLogger(RedisLoginAttempts::class.java)

    override fun fail(ip: String): Long = safely(ip) {
        val key = key(ip)
        val count = redis.opsForValue().increment(key) ?: 0L
        if (count == 1L) redis.expire(key, WINDOW)
        count
    }

    override fun failures(ip: String): Long = safely(ip) {
        redis.opsForValue().get(key(ip))?.toLongOrNull() ?: 0L
    }

    override fun clear(ip: String) {
        safely(ip) { redis.delete(key(ip)); 0L }
    }

    private fun safely(ip: String, block: () -> Long): Long = runCatching(block).getOrElse {
        log.warn("⚠️ 로그인 시도 횟수를 세지 못했습니다 (ip=$ip): ${it.message}")
        0L
    }

    private fun key(ip: String) = "admin:login:fail:$ip"

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(10)
    }
}
