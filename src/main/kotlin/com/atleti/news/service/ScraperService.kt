package com.atleti.news.service

import com.atleti.news.entity.ArticleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class ScraperService {

    suspend fun scrapeMarcaAtm(): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "https://www.marca.com/futbol/atletico.html"
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .get()
            
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun scrapeAsAtm(): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "https://as.com/noticias/atletico-madrid/"
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .referrer("https://as.com/")
                .get()
            
            // .b_col_a is the main column. .s is each story block.
            doc.select(".b_col_a .s").mapNotNull { element ->
                try {
                    val titleElement = element.select(".s_t a").firstOrNull() ?: return@mapNotNull null
                    val title = titleElement.text()
                    val link = titleElement.attr("abs:href")
                    
                    // Filter: Only actual article links (usually end with -n/, -v/, -f/)
                    val isActualArticle = link.contains(Regex("-[nvf]/?$"))
                    
                    if (isActualArticle && link.startsWith("https://as.com")) {
                        ScrapedArticle(
                            title = title,
                            url = link,
                            imageUrl = element.select("img").attr("src"),
                            sourceName = "AS",
                            articleType = ArticleType.WEB_ARTICLE,
                            authorName = "AS"
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 상세 페이지에서 실제 본문과 기자 이름, 발행 시간을 추출합니다.
     */
    suspend fun fetchFullContent(url: String): ArticleDetail = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .referrer("https://as.com/")
                .get()
            
            // 기자 이름 추출
            val authorName = doc.select(".ue-c-article__author-name, .ue-c-article__author-link, .author-name, .a_md_a_n, .s_a").firstOrNull()?.text() 
                ?: doc.select("meta[property='article:author']").attr("content").takeIf { it.isNotBlank() }
                ?: "Unknown"

            // 발행 시간 추출
            val timeStr = doc.select("time").attr("datetime")
            val publishedAt = if (timeStr.isNotBlank()) {
                try { OffsetDateTime.parse(timeStr) } catch (e: Exception) { OffsetDateTime.now() }
            } else {
                val metaTime = doc.select("meta[property='article:published_time']").attr("content")
                if (metaTime.isNotBlank()) {
                    try { OffsetDateTime.parse(metaTime) } catch (e: Exception) { OffsetDateTime.now() }
                } else OffsetDateTime.now()
            }

            // 본문 추출 (Marca: .ue-c-article__body, AS: .a_c, .article-body)
            val bodyElements = doc.select(".ue-c-article__body p, .article-content p, .a_c p, .article-body p")
            val content = if (bodyElements.isEmpty()) {
                doc.select("p").take(10).joinToString("\n\n") { it.text() }
            } else {
                bodyElements.joinToString("\n\n") { it.text() }
            }

            ArticleDetail(content = content, authorName = authorName, publishedAt = publishedAt)
        } catch (e: Exception) {
            println("❌ 상세 정보 추출 실패 ($url): ${e.message}")
            ArticleDetail(content = "", authorName = "Unknown", publishedAt = OffsetDateTime.now())
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
