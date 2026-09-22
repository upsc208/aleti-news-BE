package com.atleti.news.news.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime

/**
 * 조회수를 읽어서 더하고 저장하면 동시 조회에서 한쪽 증가가 사라진다.
 * DB에서 직접 더하는지, 두 번 부르면 2가 오르는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("h2")
class ViewCountQueryTest {

    @Autowired
    private lateinit var repository: NewsArticleRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Test
    fun `조회수를 DB에서 직접 올린다`() {
        val saved = repository.save(article())
        entityManager.flush()
        entityManager.clear()

        repository.increaseViewCount(saved.id!!)
        repository.increaseViewCount(saved.id!!)
        entityManager.flush()
        entityManager.clear()

        assertThat(repository.findById(saved.id!!).get().viewCount).isEqualTo(2L)
    }

    @Test
    fun `없는 기사면 아무 행도 바뀌지 않는다`() {
        assertThat(repository.increaseViewCount(99999L)).isEqualTo(0)
    }

    private fun article() = NewsArticle(
        articleType = ArticleType.WEB_ARTICLE,
        sourceName = "Marca",
        authorName = "Marca",
        originalUrl = "https://www.marca.com/futbol/atletico/view-count.html",
        titleKo = "조회수 테스트",
        summaryKo = null,
        contentKo = null,
        contentRaw = null,
        imageUrl = null,
        category = "[기타]",
        isAtmRelated = true,
        publishedAt = OffsetDateTime.now(),
    )
}
