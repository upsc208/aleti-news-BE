package com.atleti.news.match.api

import com.atleti.news.match.domain.FootballDataService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* 관리자 경기 정보 동기화 */
@Tag(name = "Admin Match API", description = "관리자 경기 결과·일정 동기화 API")
@RestController
@RequestMapping("/api/admin/matches")
class MatchAdminController(private val footballDataService: FootballDataService) {
    private val log = LoggerFactory.getLogger(MatchAdminController::class.java)

    @Operation(
        summary = "경기 정보 동기화",
        description = "최근 경기 결과와 향후 일정을 즉시 받아옵니다. 예정 경기 테이블은 받아온 내용으로 교체됩니다."
    )
    @PostMapping("/sync")
    suspend fun sync(): Map<String, String> {
        log.info("🔘 관리자가 경기 정보 동기화를 실행했습니다")
        return mapOf("message" to footballDataService.syncUpdate())
    }
}
