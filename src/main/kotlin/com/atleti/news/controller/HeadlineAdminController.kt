package com.atleti.news.controller

import com.atleti.news.service.HeadlineService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/headline")
class HeadlineAdminController(private val headlineService: HeadlineService) {

    @GetMapping("/trigger")
    suspend fun triggerHeadlinePipeline(): String {
        println("🔘 수동 헤드라인 파이프라인 트리거")
        headlineService.generateAndStore()
        return "헤드라인 파이프라인 실행 완료! 로그를 확인하세요."
    }
}
