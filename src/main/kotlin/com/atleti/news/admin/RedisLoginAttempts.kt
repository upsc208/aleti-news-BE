package com.atleti.news.admin

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 로그인 실패 횟수를 IP별로 Redis에 셈. 채팅 도배 방지와 같은 방식(INCR + TTL)이다.
 */
@Component
class RedisLoginAttempts(private val redis: StringRedisTemplate) : LoginAttempts {

    override fun fail(ip: String): Long {
        val key = key(ip)
        val count = redis.opsForValue().increment(key) ?: 0L
        if (count == 1L) redis.expire(key, WINDOW)
        return count
    }

    override fun clear(ip: String) {
        redis.delete(key(ip))
    }

    private fun key(ip: String) = "admin:login:fail:$ip"

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(10)
    }
}
