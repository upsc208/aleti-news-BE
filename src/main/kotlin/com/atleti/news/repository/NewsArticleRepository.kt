package com.atleti.news.repository

import com.atleti.news.entity.NewsArticle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

@Repository
interface NewsArticleRepository : JpaRepository<NewsArticle, Long> {
    fun existsByOriginalUrl(originalUrl: String): Boolean

    @Query("""
        SELECT n FROM NewsArticle n 
        WHERE (:sources IS NULL OR LOWER(n.sourceName) IN :sources)
          AND (:category IS NULL OR n.category = :category)
          AND (:search IS NULL OR LOWER(n.titleKo) LIKE LOWER(CONCAT('%', :search, '%')) 
                               OR LOWER(n.contentKo) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun searchNews(
        @Param("sources") sources: List<String>?,
        @Param("category") category: String?,
        @Param("search") search: String?,
        pageable: Pageable
    ): Page<NewsArticle>

    fun findAllBySourceNameIn(sourceNames: List<String>, pageable: Pageable): Page<NewsArticle>
    fun findAllByCategory(category: String, pageable: Pageable): Page<NewsArticle>
    fun findAllByCategoryAndSourceNameIn(category: String, sourceNames: List<String>, pageable: Pageable): Page<NewsArticle>
}
