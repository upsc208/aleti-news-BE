package com.atleti.news.controller

import com.atleti.news.service.YoutubeService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/youtube")
class YoutubeAdminController(private val youtubeService: YoutubeService) {

    @GetMapping("/trigger")
    suspend fun triggerYoutubePipeline(@RequestParam(required = false, defaultValue = "@rubenuriagonzalez") channelId: String): String {
        println("🔘 수동 유튜브 파이프라인 트리거: $channelId")
        youtubeService.runYoutubePipeline(channelId)
        return "유튜브 파이프라인 실행 완료! 로그를 확인하세요."
    }
}
