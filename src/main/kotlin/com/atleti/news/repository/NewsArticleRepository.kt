package com.atleti.news.repository

import com.atleti.news.entity.NewsArticle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NewsArticleRepository : JpaRepository<NewsArticle, Long> {
    fun existsByOriginalUrl(originalUrl: String): Boolean
}
