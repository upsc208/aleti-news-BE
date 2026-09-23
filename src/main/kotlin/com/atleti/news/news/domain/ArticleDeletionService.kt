package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/* 기사 삭제. 지운 주소를 남겨 둠. 삭제한 기사를 다시 수집 안하게 하기 위함*/
@Service
class ArticleDeletionService(
    private val newsArticleRepository: NewsArticleRepository,
    private val deletedArticleRepository: DeletedArticleRepository,
) {
    private val log = LoggerFactory.getLogger(ArticleDeletionService::class.java)

    @Transactional
    fun delete(id: Long): NewsArticle? {
        val article = newsArticleRepository.findById(id).orElse(null) ?: return null

        if (!deletedArticleRepository.existsByOriginalUrl(article.originalUrl)) {
            deletedArticleRepository.save(
                DeletedArticle(originalUrl = article.originalUrl, titleKo = article.titleKo)
            )
        }
        newsArticleRepository.deleteById(id)

        log.warn("🗑️ 관리자가 기사를 삭제했습니다: id=$id, 제목=${article.titleKo}")
        return article
    }

    fun isDeleted(originalUrl: String): Boolean = deletedArticleRepository.existsByOriginalUrl(originalUrl)
}
