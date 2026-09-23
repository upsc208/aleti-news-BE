package com.atleti.news.news.api

import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.HeadlineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Headline API", description = "관리자 헤드라인 브리핑 수동 실행 API")
@RestController
@RequestMapping("/api/admin/headline")
class HeadlineAdminController(private val headlineService: HeadlineService) {
    private val log = LoggerFactory.getLogger(HeadlineAdminController::class.java)

    @Operation(
        summary = "헤드라인 수동 생성",
        description = "직전 생성 시각 이후에 모인 기사로 헤드라인을 만들고 최신 5개만 남깁니다."
    )
    @GetMapping("/trigger")
    suspend fun triggerHeadlinePipeline(): String {
        log.info("🔘 수동 헤드라인 파이프라인 트리거")
        headlineService.generateAndStore()
        return "헤드라인 파이프라인 실행 완료! 로그를 확인하세요."
    }
}
