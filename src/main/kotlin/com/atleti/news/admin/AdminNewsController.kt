package com.atleti.news.admin

import com.atleti.news.news.domain.NewsArticleRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자만 허용되는 기사 관리
 */
@RestController
@RequestMapping("/api/admin/news")
class AdminNewsController(private val newsArticleRepository: NewsArticleRepository) {
    private val log = LoggerFactory.getLogger(AdminNewsController::class.java)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Map<String, Any>> {
        val article = newsArticleRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "기사를 찾을 수 없습니다."))

        newsArticleRepository.deleteById(id)
        log.warn("🗑️ 관리자가 기사를 삭제했습니다: id=$id, 제목=${article.titleKo}")
        return ResponseEntity.ok(mapOf("deleted" to id, "titleKo" to (article.titleKo ?: "")))
    }
}
