package com.atleti.news.controller

import com.atleti.news.service.PlayerRatingResponse
import com.atleti.news.service.PlayerRatingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*

@Tag(name = "Player Rating API", description = "경기 선수 평점 (로그인 없이 IP 기반, 0~5점)")
@RestController
@RequestMapping("/api/matches/{matchId}/ratings")
class PlayerRatingController(
    private val playerRatingService: PlayerRatingService
) {

    @Operation(
        summary = "선수 평점 목록 조회",
        description = "해당 경기에서 뛴 아틀레티코 선수별 평균 평점, 투표수, 그리고 요청 IP가 준 점수(myScore)를 반환합니다."
    )
    @GetMapping
    fun getRatings(@PathVariable matchId: String, request: HttpServletRequest): List<PlayerRatingResponse> {
        return playerRatingService.getRatings(matchId, clientIp(request))
    }

    @Operation(
        summary = "선수 평점 등록/수정",
        description = "0~5점으로 선수 평점을 남깁니다. 같은 IP가 다시 보내면 점수가 수정됩니다. 응답으로 갱신된 평균/투표수를 즉시 돌려줍니다."
    )
    @PostMapping
    fun submitRating(
        @PathVariable matchId: String,
        @RequestBody req: PlayerRatingRequest,
        request: HttpServletRequest
    ): PlayerRatingResponse {
        return playerRatingService.submitRating(matchId, req.playerName, req.score, clientIp(request))
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim() ?: request.remoteAddr
}

data class PlayerRatingRequest(
    val playerName: String,
    val score: Int
)
