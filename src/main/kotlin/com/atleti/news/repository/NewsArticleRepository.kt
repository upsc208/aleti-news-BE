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
        WHERE (:sources IS NULL OR LOWER(CAST(n.sourceName AS string)) IN :sources)
          AND (:category IS NULL OR n.category = :category)
          AND (:search IS NULL OR LOWER(CAST(n.titleKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) 
                               OR LOWER(CAST(n.contentKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
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

    @Query("""
        SELECT n FROM NewsArticle n 
        WHERE n.viewCount >= :minViewCount 
          AND n.publishedAt >= :since
          AND (:sources IS NULL OR LOWER(CAST(n.sourceName AS string)) IN :sources)
          AND (:category IS NULL OR n.category = :category)
    """)
    fun searchHypedNews(
        @Param("minViewCount") minViewCount: Long,
        @Param("since") since: java.time.OffsetDateTime,
        @Param("sources") sources: List<String>?,
        @Param("category") category: String?,
        pageable: Pageable
    ): Page<NewsArticle>
}
