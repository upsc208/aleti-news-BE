package com.atleti.news.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class NitterScraperService {
    // Fail-over를 위한 인스턴스 리스트
    private val nitterInstances = listOf(
        "https://nitter.net",
        "https://nitter.cz",
        "https://nitter.it",
        "https://nitter.privacydev.net"
    )

    suspend fun scrapeJournalist(username: String): List<ScrapedArticle> {
        for (instance in nitterInstances) {
            try {
                return scrapeFromInstance(instance, username)
            } catch (e: Exception) {
                println("Nitter 인스턴스 실패 ($instance): \${e.message}. 다음 인스턴스로 시도합니다.")
                continue
            }
        }
        return emptyList()
    }

    private suspend fun scrapeFromInstance(baseUrl: String, username: String): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "\$baseUrl/\$username"
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get()
        
        doc.select(".timeline-item").mapNotNull { element ->
            val content = element.select(".tweet-content").text()
            val link = element.select(".tweet-link").attr("abs:href")
            val publishedAt = element.select(".tweet-date a").attr("title")
            
            if (content.isNotBlank() && link.isNotBlank()) {
                ScrapedArticle(
                    title = content.take(100) + if (content.length > 100) "..." else "",
                    url = link,
                    imageUrl = null, // 트위터 이미지는 별도 파싱 필요
                    sourceName = "Twitter (@\$username)"
                )
            } else null
        }
    }
}
