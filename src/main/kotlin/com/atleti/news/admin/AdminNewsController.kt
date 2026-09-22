package com.atleti.news.admin

import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.NewsPipelineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* 관리자 기사 관리 */
@Tag(name = "Admin News API", description = "관리자 기사 삭제 및 수집 수동 실행 API")
@RestController
@RequestMapping("/api/admin/news")
class AdminNewsController(
    private val newsArticleRepository: NewsArticleRepository,
    private val newsPipelineService: NewsPipelineService,
) {
    private val log = LoggerFactory.getLogger(AdminNewsController::class.java)

    @Operation(summary = "기사 삭제", description = "기사를 영구 삭제합니다. 없는 기사면 404입니다.")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Map<String, Any>> {
        val article = newsArticleRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "기사를 찾을 수 없습니다."))

        newsArticleRepository.deleteById(id)
        log.warn("🗑️ 관리자가 기사를 삭제했습니다: id=$id, 제목=${article.titleKo}")
        return ResponseEntity.ok(mapOf("deleted" to id, "titleKo" to (article.titleKo ?: "")))
    }

    @Operation(
        summary = "뉴스 수집 수동 실행",
        description = "수집을 백그라운드에서 시작하고 바로 응답합니다(202). 이미 돌고 있으면 409입니다."
    )
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
