package com.atleti.news.repository

import com.atleti.news.entity.NewsArticle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

@Repository
interface NewsArticleRepository : JpaRepository<NewsArticle, Long> {
    fun existsByOriginalUrl(originalUrl: String): Boolean
    fun findAllBySourceNameIn(sourceNames: List<String>, pageable: Pageable): Page<NewsArticle>
}
