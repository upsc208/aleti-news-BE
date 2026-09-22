package com.atleti.news.news.api

import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.YoutubeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin YouTube API", description = "관리자 유튜브 라이브 요약 수동 실행 API")
@RestController
@RequestMapping("/api/admin/youtube")
class YoutubeAdminController(private val youtubeService: YoutubeService) {
    private val log = LoggerFactory.getLogger(YoutubeAdminController::class.java)

    @Operation(
        summary = "유튜브 요약 수동 실행",
        description = "최신 라이브 영상의 자막을 받아 번역·요약합니다. 새 영상일 때만 SerpApi 쿼터를 1회 씁니다."
    )
    @GetMapping("/trigger")
    suspend fun triggerYoutubePipeline(@RequestParam(required = false, defaultValue = "@rubenuriagonzalez") channelId: String): String {
        log.info("🔘 수동 유튜브 파이프라인 트리거: $channelId")
        youtubeService.runYoutubePipeline(channelId)
        return "유튜브 파이프라인 실행 완료! 로그를 확인하세요."
    }
}
