package com.atleti.news.service

import com.atleti.news.entity.ArticleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class ScraperService {

    suspend fun scrapeMarcaAtm(): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "https://www.marca.com/futbol/atletico.html"
        val doc = Jsoup.connect(url).get()
        
        doc.select("article").mapNotNull { element ->
            try {
                val title = element.select("h2, h3").text()
                val link = element.select("a").attr("abs:href")
                val imageUrl = element.select("img").attr("src")
                
                if (title.isNotBlank() && link.isNotBlank() && link.contains("marca.com")) {
                    ScrapedArticle(
                        title = title,
                        url = link,
                        imageUrl = imageUrl,
                        sourceName = "Marca",
                        articleType = ArticleType.WEB_ARTICLE,
                        authorName = "Marca"
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 상세 페이지에서 실제 본문과 기자 이름, 발행 시간을 추출합니다.
     */
    suspend fun fetchFullContent(url: String): ArticleDetail = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url).get()
            
            // 기자 이름 추출
            val authorName = doc.select(".ue-c-article__author-name, .ue-c-article__author-link, .author-name").firstOrNull()?.text() 
                ?: "Marca"

            // 발행 시간 추출 (Marca 타임스탬프 태그 대응)
            val timeStr = doc.select("time").attr("datetime")
            val publishedAt = if (timeStr.isNotBlank()) {
                try { OffsetDateTime.parse(timeStr) } catch (e: Exception) { OffsetDateTime.now() }
            } else OffsetDateTime.now()

            // 본문 추출
            val bodyElements = doc.select(".ue-c-article__body p, .article-content p")
            val content = if (bodyElements.isEmpty()) {
                doc.select("p").take(10).joinToString("\n\n") { it.text() }
            } else {
                bodyElements.joinToString("\n\n") { it.text() }
            }

            ArticleDetail(content = content, authorName = authorName, publishedAt = publishedAt)
        } catch (e: Exception) {
            println("❌ 상세 정보 추출 실패 ($url): ${e.message}")
            ArticleDetail(content = "", authorName = "Marca", publishedAt = OffsetDateTime.now())
        }
    }
}

data class ArticleDetail(
    val content: String,
    val authorName: String,
    val publishedAt: java.time.OffsetDateTime
)

data class ScrapedArticle(
    val title: String,
    val url: String,
    val imageUrl: String?,
    val sourceName: String,
    val articleType: ArticleType,
    val authorName: String?,
    val publishedAt: java.time.OffsetDateTime = java.time.OffsetDateTime.now()
)
