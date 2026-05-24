package com.atleti.news.service

import com.atleti.news.entity.ArticleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class ScraperService {

    private val commonUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    suspend fun scrapeMarcaAtm(): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "https://www.marca.com/futbol/atletico.html"
        try {
            kotlinx.coroutines.delay((1000..2000).random().toLong())
            val doc = Jsoup.connect(url)
                .userAgent(commonUserAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .timeout(30000)
                .get()
            
            val articles = doc.select("article.ue-c-cover-content, .ue-c-main-article, .ue-c-cover-content__main article").mapNotNull { element ->
                try {
                    val titleElement = element.select("h2, h3").firstOrNull() ?: return@mapNotNull null
                    val title = titleElement.text()
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
            println("✅ [Marca Scrape] ${articles.size} articles found.")
            articles
        } catch (e: Exception) {
            println("❌ [Marca Scrape] Failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun scrapeAsAtm(): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "https://as.com/noticias/atletico-madrid/"
        try {
            kotlinx.coroutines.delay((1000..2000).random().toLong())
            val doc = Jsoup.connect(url)
                .userAgent(commonUserAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .referrer("https://as.com/")
                .timeout(30000)
                .get()
            
            val articles = doc.select(".b_col_a .s").mapNotNull { element ->
                try {
                    val titleElement = element.select(".s_t a").firstOrNull() ?: return@mapNotNull null
                    val title = titleElement.text()
                    val link = titleElement.attr("abs:href")
                    
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
            println("✅ [AS Scrape] ${articles.size} articles found.")
            articles
        } catch (e: Exception) {
            println("❌ [AS Scrape] Failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchFullContent(url: String): ArticleDetail = withContext(Dispatchers.IO) {
        val SEOUL_ZONE = java.time.ZoneId.of("Asia/Seoul")
        var lastException: Exception? = null

        // 최대 2회 재시도 로직 추가
        for (retry in 1..2) {
            try {
                kotlinx.coroutines.delay((2000..4000).random().toLong())
                
                val connection = Jsoup.connect(url)
                    .userAgent(commonUserAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A:Brand\";v=\"24\"")
                    .header("Sec-Ch-Ua-Mobile", "?0")
                    .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .referrer(if (url.contains("as.com")) "https://as.com/" else "https://www.marca.com/")
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .timeout(30000)

                val response = connection.execute()
                val status = response.statusCode()

                if (status == 403) {
                    println("⚠️ [Scraper 403] $url - Access denied (WAF Blocked). Retry $retry/2...")
                    continue // 재시도
                }

                val doc = response.parse()
                
                val authorName = doc.select(".ue-c-article__author-name, .ue-c-article__author-link, .author-name, .a_md_a_n, .s_a").firstOrNull()?.text() 
                    ?: doc.select("meta[property='article:author']").attr("content").takeIf { it.isNotBlank() }
                    ?: "Unknown"

                fun parseToKST(raw: String?): OffsetDateTime? {
                    if (raw.isNullOrBlank()) return null
                    return try {
                        OffsetDateTime.parse(raw).atZoneSameInstant(SEOUL_ZONE).toOffsetDateTime()
                    } catch (e: Exception) {
                        try {
                            val instant = java.time.Instant.ofEpochSecond(raw.toLong())
                            OffsetDateTime.ofInstant(instant, SEOUL_ZONE)
                        } catch (e2: Exception) { null }
                    }
                }

                val publishedAt = parseToKST(doc.select("meta[property='article:published_time']").attr("content"))
                    ?: parseToKST(doc.select("meta[property='article:modified_time']").attr("content"))
                    ?: parseToKST(doc.select("meta[name='date']").attr("content"))
                    ?: parseToKST(doc.select(".ue-c-article__publishdate time").attr("datetime"))
                    ?: parseToKST(doc.select("time").attr("datetime"))
                    ?: OffsetDateTime.now(SEOUL_ZONE)

                val bodyElements = doc.select(".ue-c-article__body p, .article-content p, .a_c p, .article-body p")
                val content = if (bodyElements.isEmpty()) {
                    doc.select("p").take(10).joinToString("\n\n") { it.text() }
                } else {
                    bodyElements.joinToString("\n\n") { it.text() }
                }

                val highResImageUrl = doc.select("meta[property='og:image']").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select("meta[name='twitter:image']").attr("content").takeIf { it.isNotBlank() }

                return@withContext ArticleDetail(content = content, authorName = authorName, publishedAt = publishedAt, imageUrl = highResImageUrl)

            } catch (e: Exception) {
                lastException = e
                println("⚠️ [Scraper Retry] 상세 정보 추출 시도 중 오류 ($url): ${e.message}. Retry $retry/2...")
            }
        }

        println("❌ 상세 정보 최종 추출 실패 ($url): ${lastException?.message}")
        return@withContext ArticleDetail(content = "", authorName = "Unknown", publishedAt = OffsetDateTime.now(SEOUL_ZONE), imageUrl = null)
    }
}

data class ArticleDetail(
    val content: String,
    val authorName: String,
    val publishedAt: java.time.OffsetDateTime,
    val imageUrl: String? = null
)

data class ScrapedArticle(
    val title: String,
    val url: String,
    val imageUrl: String?,
    val sourceName: String,
    val articleType: ArticleType,
    val authorName: String?,
    val publishedAt: java.time.OffsetDateTime = java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
)
