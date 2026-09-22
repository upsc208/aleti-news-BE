package com.atleti.news.admin

import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.NewsPipelineService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* 관리자 기사 관리 */
@RestController
@RequestMapping("/api/admin/news")
class AdminNewsController(
    private val newsArticleRepository: NewsArticleRepository,
    private val newsPipelineService: NewsPipelineService,
) {
    private val log = LoggerFactory.getLogger(AdminNewsController::class.java)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Map<String, Any>> {
        val article = newsArticleRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "기사를 찾을 수 없습니다."))

        newsArticleRepository.deleteById(id)
        log.warn("🗑️ 관리자가 기사를 삭제했습니다: id=$id, 제목=${article.titleKo}")
        return ResponseEntity.ok(mapOf("deleted" to id, "titleKo" to (article.titleKo ?: "")))
    }

    @PostMapping("/trigger")
    fun trigger(): ResponseEntity<Map<String, Any>> {
        if (!newsPipelineService.startPipeline()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("started" to false, "message" to "수집이 이미 돌고 있습니다."))
        }

        log.info("🔘 관리자가 뉴스 수집을 실행했습니다")
        return ResponseEntity.accepted()
            .body(mapOf("started" to true, "message" to "수집을 시작했습니다. 몇 분 뒤 기사 목록을 확인하세요."))
    }
}
