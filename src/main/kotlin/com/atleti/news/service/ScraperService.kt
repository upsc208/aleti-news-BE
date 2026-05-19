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
            kotlinx.coroutines.delay((500..1500).random().toLong())
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "max-age=0")
                .get()
            
            // Marca ATM 페이지의 기사 선택자 업데이트
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
            kotlinx.coroutines.delay((500..1500).random().toLong())
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Cache-Control", "max-age=0")
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
            println("❌ [AS Scrape] Failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 상세 페이지에서 실제 본문과 기자 이름, 발행 시간을 추출합니다.
     */
    suspend fun fetchFullContent(url: String): ArticleDetail = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.delay((1000..2000).random().toLong())
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "max-age=0")
                .referrer(if (url.contains("as.com")) "https://as.com/" else "https://www.marca.com/")
                .get()
            
            // 기자 이름 추출
            val authorName = doc.select(".ue-c-article__author-name, .ue-c-article__author-link, .author-name, .a_md_a_n, .s_a").firstOrNull()?.text() 
                ?: doc.select("meta[property='article:author']").attr("content").takeIf { it.isNotBlank() }
                ?: "Unknown"

            // 발행 시간 추출
            val SEOUL_ZONE = java.time.ZoneId.of("Asia/Seoul")
            
            fun parseToKST(raw: String?): OffsetDateTime? {
                if (raw.isNullOrBlank()) return null
                return try {
                    // ISO 8601 (e.g., 2026-05-12T21:39:44Z)
                    OffsetDateTime.parse(raw)
                        .atZoneSameInstant(SEOUL_ZONE)
                        .toOffsetDateTime()
                } catch (e: Exception) {
                    try {
                        // Unix Timestamp (e.g., 1778582674)
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

            // 본문 추출 (Marca: .ue-c-article__body, AS: .a_c, .article-body)
            val bodyElements = doc.select(".ue-c-article__body p, .article-content p, .a_c p, .article-body p")
            val content = if (bodyElements.isEmpty()) {
                doc.select("p").take(10).joinToString("\n\n") { it.text() }
            } else {
                bodyElements.joinToString("\n\n") { it.text() }
            }

            // 고화질 이미지 추출 (og:image 메타 태그 활용)
            val highResImageUrl = doc.select("meta[property='og:image']").attr("content").takeIf { it.isNotBlank() }
                ?: doc.select("meta[name='twitter:image']").attr("content").takeIf { it.isNotBlank() }

            ArticleDetail(
                content = content, 
                authorName = authorName, 
                publishedAt = publishedAt,
                imageUrl = highResImageUrl
            )
        } catch (e: Exception) {
            val SEOUL_ZONE = java.time.ZoneId.of("Asia/Seoul")
            println("❌ 상세 정보 추출 실패 ($url): ${e.message}")
            ArticleDetail(content = "", authorName = "Unknown", publishedAt = OffsetDateTime.now(SEOUL_ZONE), imageUrl = null)
        }
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
