package com.atleti.news.match.api

import com.atleti.news.match.domain.FootballDataService
import com.atleti.news.match.domain.RecentMatch
import com.atleti.news.match.domain.UpcomingMatchResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Match API", description = "아틀레티코 경기 결과 및 일정 API")
@RestController
@RequestMapping("/api/matches")
class MatchController(
    private val footballDataService: FootballDataService
) {

    @Operation(summary = "최근 경기 결과 조회", description = "가장 최근 종료된 경기 1개의 상세 결과를 가져옵니다.")
    @GetMapping("/recent")
    suspend fun getRecentMatch(): RecentMatch? {
        return footballDataService.getRecentMatch()
    }

    @Operation(summary = "향후 경기 일정 조회", description = "다가올 3경기의 일정을 가져옵니다.")
    @GetMapping("/upcoming")
    suspend fun getUpcomingMatches(): List<UpcomingMatchResponse> {
        return footballDataService.getUpcomingMatches()
    }

    @Operation(summary = "[테스트용] 경기 정보 강제 동기화", description = "백그라운드가 아닌 즉시 API를 호출하여 데이터를 업데이트합니다.")
    @GetMapping("/sync")
    suspend fun syncMatches(): String {
        return footballDataService.syncUpdate()
    }
}
