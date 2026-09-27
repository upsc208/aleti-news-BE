package com.atleti.news.news.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * 조회수 증가는 Redis 판정이 끝난 뒤에만 일어나야 한다.
 * Redis가 이미 본 IP라고 하거나 장애로 실패하면 DB를 건드리지 않는다.
 */
@Suppress("UNCHECKED_CAST")
class ViewCountServiceTest {

    private val redis = mock(StringRedisTemplate::class.java)
    private val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val repository = mock(NewsArticleRepository::class.java)
    private val service = ViewCountService(redis, repository)

    @Test
    fun `처음 보는 IP면 조회수를 올린다`() {
        markerReturns(true)

        assertThat(service.countView(7L, "1.2.3.4")).isTrue()
        verify(repository).increaseViewCount(7L)
    }

    @Test
    fun `이미 본 IP면 DB를 건드리지 않는다`() {
        markerReturns(false)

        assertThat(service.countView(7L, "1.2.3.4")).isFalse()
        verify(repository, never()).increaseViewCount(anyLong())
    }

    @Test
    fun `Redis가 죽어도 기사 조회는 계속된다`() {
        `when`(redis.opsForValue()).thenThrow(RuntimeException("connection refused"))

        assertThat(service.countView(7L, "1.2.3.4")).isFalse()
        verify(repository, never()).increaseViewCount(anyLong())
    }

    private fun markerReturns(firstView: Boolean) {
        `when`(redis.opsForValue()).thenReturn(valueOps)
        `when`(valueOps.setIfAbsent(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration::class.java)))
            .thenReturn(firstView)
    }
}
