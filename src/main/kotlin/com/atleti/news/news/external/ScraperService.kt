package com.atleti.news.news.external

import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.ArticleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class ScraperService {
    private val log = LoggerFactory.getLogger(ScraperService::class.java)


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
            
            val articles = parseMarcaList(doc)
            log.info("✅ [Marca Scrape] ${articles.size} articles found.")
            articles
        } catch (e: Exception) {
            log.error("❌ [Marca Scrape] Failed: ${e.message}", e)
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
            
            val articles = parseAsList(doc)
            log.info("✅ [AS Scrape] ${articles.size} articles found.")
            articles
        } catch (e: Exception) {
            log.error("❌ [AS Scrape] Failed: ${e.message}", e)
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
                    log.warn("⚠️ [Scraper 403] $url - Access denied (WAF Blocked). Retry $retry/2...")
                    continue // 재시도
                }

                return@withContext parseArticleDetail(response.parse(), OffsetDateTime.now(SEOUL_ZONE))

            } catch (e: Exception) {
                lastException = e
                log.warn("⚠️ [Scraper Retry] 상세 정보 추출 시도 중 오류 ($url): ${e.message}. Retry $retry/2...", e)
            }
        }

        log.error("❌ 상세 정보 최종 추출 실패 ($url): ${lastException?.message}")
        return@withContext ArticleDetail(content = "", authorName = "Unknown", publishedAt = OffsetDateTime.now(SEOUL_ZONE), imageUrl = null)
    }

    companion object {
        internal val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 마르카 목록 페이지 → 기사 후보. 순수 함수다 (Jsoup [Document]만 받는다).
         *
         * `marca.com`이 아닌 링크를 버린다. 목록에 광고·제휴 매체 링크가 섞여 들어온다.
         */
        internal fun parseMarcaList(doc: Document): List<ScrapedArticle> =
            doc.select("article.ue-c-cover-content, .ue-c-main-article, .ue-c-cover-content__main article")
                .mapNotNull { element ->
                    // 항목 하나가 터져도 그것만 버린다. 여기서 예외가 밖으로 나가면
                    // 호출부의 catch가 받아 **목록 전체가 빈 리스트**가 된다
                    runCatching {
                        val title = element.select("h2, h3").firstOrNull()?.text() ?: return@runCatching null
                        val link = element.select("a").attr("abs:href")
                        if (title.isBlank() || link.isBlank() || !link.contains("marca.com")) return@runCatching null

                        ScrapedArticle(
                            title = title,
                            url = link,
                            imageUrl = element.select("img").attr("src"),
                            sourceName = "Marca",
                            articleType = ArticleType.WEB_ARTICLE,
                            authorName = "Marca"
                        )
                    }.getOrNull()
                }

        /**
         * AS 목록 페이지 → 기사 후보. 순수 함수다.
         *
         * **기사와 섹션 링크를 URL 접미사로 가른다.** AS는 기사 URL을 `-n` `-v` `-f`로 끝내는데
         * (뉴스·영상·포토), 목록에는 섹션·태그 링크도 같이 들어 있다. 이걸 안 거르면
         * 기사가 아닌 페이지를 본문 추출까지 돌려 쿼터와 시간을 버린다.
         */
        internal fun parseAsList(doc: Document): List<ScrapedArticle> =
            doc.select(".b_col_a .s").mapNotNull { element ->
                // [parseMarcaList]와 같은 이유로 항목 단위로 감싼다
                runCatching {
                    val titleElement = element.select(".s_t a").firstOrNull() ?: return@runCatching null
                    val link = titleElement.attr("abs:href")
                    if (!link.contains(AS_ARTICLE_SUFFIX) || !link.startsWith("https://as.com")) return@runCatching null

                    ScrapedArticle(
                        title = titleElement.text(),
                        url = link,
                        imageUrl = element.select("img").attr("src"),
                        sourceName = "AS",
                        articleType = ArticleType.WEB_ARTICLE,
                        authorName = "AS"
                    )
                }.getOrNull()
            }

        private val AS_ARTICLE_SUFFIX = Regex("-[nvf]/?$")

        /**
         * 기사 상세 페이지 → [ArticleDetail]. 순수 함수다 — [now]를 받으므로 결과가 재현된다.
         *
         * **거의 전부가 폴백 사슬이다.** 마르카와 AS가 서로 다른 마크업을 쓰고, 같은 매체 안에서도
         * 기사 종류마다 다르다. 어느 한 단계가 조용히 안 맞아도 다음이 받아 주도록 짜여 있는데,
         * 그래서 **깨져도 티가 안 난다** — 작성자가 전부 "Unknown"이 되거나 발행일이 전부 지금으로
         * 찍혀도 예외는 안 난다. 이 폴백들이 이 클래스에서 테스트가 가장 필요한 부분이다.
         */
        internal fun parseArticleDetail(doc: Document, now: OffsetDateTime): ArticleDetail {
            // 셀렉터가 맞았는데 텍스트가 비면 그 빈 값을 그대로 쓴다 — 옛 동작 그대로다.
            // 메타로 내려가게 하는 편이 나아 보이지만, 그건 동작 변경이라 이 커밋의 범위가 아니다.
            val authorName = doc
                .select(".ue-c-article__author-name, .ue-c-article__author-link, .author-name, .a_md_a_n, .s_a")
                .firstOrNull()?.text()
                ?: doc.select("meta[property='article:author']").attr("content").takeIf { it.isNotBlank() }
                ?: "Unknown"

            val publishedAt = parseToKst(doc.select("meta[property='article:published_time']").attr("content"))
                ?: parseToKst(doc.select("meta[property='article:modified_time']").attr("content"))
                ?: parseToKst(doc.select("meta[name='date']").attr("content"))
                ?: parseToKst(doc.select(".ue-c-article__publishdate time").attr("datetime"))
                ?: parseToKst(doc.select("time").attr("datetime"))
                ?: now

            val bodyElements = doc.select(".ue-c-article__body p, .article-content p, .a_c p, .article-body p")
            val content = if (bodyElements.isEmpty()) {
                // 알려진 셀렉터가 하나도 안 맞으면 앞쪽 문단으로 근사한다.
                // 개수를 제한하는 이유는 꼬리에 붙는 관련기사·약관 문구를 본문으로 삼지 않기 위해서다
                doc.select("p").take(FALLBACK_PARAGRAPHS).joinToString("\n\n") { it.text() }
            } else {
                bodyElements.joinToString("\n\n") { it.text() }
            }

            val imageUrl = doc.select("meta[property='og:image']").attr("content").takeIf { it.isNotBlank() }
                ?: doc.select("meta[name='twitter:image']").attr("content").takeIf { it.isNotBlank() }

            return ArticleDetail(content = content, authorName = authorName, publishedAt = publishedAt, imageUrl = imageUrl)
        }

        private const val FALLBACK_PARAGRAPHS = 10

        /**
         * 기사 시각 문자열 → 서울 시간대. 못 읽으면 null (호출부가 다음 후보로 넘어간다).
         *
         * 두 형식을 받는다 — ISO-8601과 epoch 초. 매체가 섞어 쓴다.
         */
        internal fun parseToKst(raw: String?): OffsetDateTime? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                OffsetDateTime.parse(raw).atZoneSameInstant(SEOUL).toOffsetDateTime()
            }.recoverCatching {
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(raw.toLong()), SEOUL)
            }.getOrNull()
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
    val publishedAt: java.time.OffsetDateTime = java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Seoul")),
    val isRetweet: Boolean = false
)
