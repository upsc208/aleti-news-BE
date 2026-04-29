package com.atleti.news.repository

import com.atleti.news.entity.NewsSource
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NewsSourceRepository : JpaRepository<NewsSource, Long> {
    fun findByInternalId(internalId: String): NewsSource?
    fun findAllByIsActiveTrue(): List<NewsSource>
}
