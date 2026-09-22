package com.atleti.news.admin

import com.atleti.news.news.domain.ArticleType
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.NewsPipelineService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.Optional

class AdminNewsControllerTest {

    private val repository = mock(NewsArticleRepository::class.java)
    private val pipeline = mock(NewsPipelineService::class.java)
    private val controller = AdminNewsController(repository, pipeline)

    @Test
    fun `기사를 지운다`() {
        `when`(repository.findById(7763L)).thenReturn(Optional.of(article()))

        val response = controller.delete(7763L)

        verify(repository).deleteById(7763L)
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.get("deleted")).isEqualTo(7763L)
    }

    @Test
    fun `없는 기사는 404로 답하고 아무것도 지우지 않는다`() {
        `when`(repository.findById(1L)).thenReturn(Optional.empty())

        val response = controller.delete(1L)

        assertThat(response.statusCode.value()).isEqualTo(404)
        verify(repository, never()).deleteById(1L)
    }

    @Test
    fun `수집을 시작하면 기다리지 않고 202로 답한다`() {
        `when`(pipeline.startPipeline()).thenReturn(true)

        val response = controller.trigger()

        assertThat(response.statusCode.value()).isEqualTo(202)
        assertThat(response.body?.get("started")).isEqualTo(true)
    }

    @Test
    fun `이미 돌고 있으면 새로 띄우지 않는다`() {
        `when`(pipeline.startPipeline()).thenReturn(false)

        val response = controller.trigger()

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body?.get("started")).isEqualTo(false)
    }

    private fun article() = NewsArticle(
        articleType = ArticleType.YOUTUBE,
        sourceName = "YouTube",
        authorName = "Ruben Uria",
        originalUrl = "https://www.youtube.com/watch?v=test",
        titleKo = "테스트 기사",
        summaryKo = null,
        contentKo = null,
        contentRaw = null,
        imageUrl = null,
        category = "라이브요약",
        isAtmRelated = true,
        publishedAt = OffsetDateTime.now(),
    )
}
