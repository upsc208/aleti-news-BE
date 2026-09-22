package com.atleti.news.news.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.Optional

/**
 * 하드 삭제만 하면 다음 수집에서 같은 기사가 되살아난다 — 중복 판정이 원문 주소뿐이기 때문.
 * 지운 주소를 남기는지, 그 주소를 수집이 걸러낼 수 있는지 고정한다.
 */
class ArticleDeletionServiceTest {

    private val articles = mock(NewsArticleRepository::class.java)
    private val deleted = mock(DeletedArticleRepository::class.java)
    private val service = ArticleDeletionService(articles, deleted)

    @Test
    fun `지울 때 원문 주소를 남긴다`() {
        `when`(articles.findById(12L)).thenReturn(Optional.of(article()))
        `when`(deleted.existsByOriginalUrl(URL)).thenReturn(false)

        val result = service.delete(12L)

        val saved = ArgumentCaptor.forClass(DeletedArticle::class.java)
        verify(deleted).save(saved.capture())
        assertThat(saved.value.originalUrl).isEqualTo(URL)
        assertThat(saved.value.titleKo).isEqualTo("테스트 기사")
        verify(articles).deleteById(12L)
        assertThat(result?.titleKo).isEqualTo("테스트 기사")
    }

    @Test
    fun `이미 남아 있는 주소는 다시 넣지 않는다`() {
        `when`(articles.findById(12L)).thenReturn(Optional.of(article()))
        `when`(deleted.existsByOriginalUrl(URL)).thenReturn(true)

        service.delete(12L)

        verify(deleted, never()).save(org.mockito.ArgumentMatchers.any(DeletedArticle::class.java))
        verify(articles).deleteById(12L)
    }

    @Test
    fun `없는 기사는 아무것도 지우지 않는다`() {
        `when`(articles.findById(99L)).thenReturn(Optional.empty())

        val result = service.delete(99L)

        assertThat(result).isNull()
        verify(articles, never()).deleteById(99L)
    }

    @Test
    fun `지운 주소인지 물어볼 수 있다`() {
        `when`(deleted.existsByOriginalUrl(URL)).thenReturn(true)

        assertThat(service.isDeleted(URL)).isTrue()
    }

    private fun article() = NewsArticle(
        articleType = ArticleType.WEB_ARTICLE,
        sourceName = "Marca",
        authorName = "Marca",
        originalUrl = URL,
        titleKo = "테스트 기사",
        summaryKo = null,
        contentKo = null,
        contentRaw = null,
        imageUrl = null,
        category = "[기타]",
        isAtmRelated = true,
        publishedAt = OffsetDateTime.now(),
    )

    private companion object {
        const val URL = "https://www.marca.com/futbol/atletico/test.html"
    }
}
