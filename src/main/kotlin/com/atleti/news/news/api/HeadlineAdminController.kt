package com.atleti.news.news.api

import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.HeadlineService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/headline")
class HeadlineAdminController(private val headlineService: HeadlineService) {
    private val log = LoggerFactory.getLogger(HeadlineAdminController::class.java)


    @GetMapping("/trigger")
    suspend fun triggerHeadlinePipeline(): String {
        log.info("🔘 수동 헤드라인 파이프라인 트리거")
        headlineService.generateAndStore()
        return "헤드라인 파이프라인 실행 완료! 로그를 확인하세요."
    }
}
