package com.atleti.news.news.external

import com.atleti.news.news.external.ScraperService.Companion.parseArticleDetail
import com.atleti.news.news.external.ScraperService.Companion.parseAsList
import com.atleti.news.news.external.ScraperService.Companion.parseMarcaList
import com.atleti.news.news.external.ScraperService.Companion.parseToKst
import org.jsoup.Jsoup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 마르카·AS 스크래핑의 해석 로직 검증.
 *
 * 이 클래스는 **거의 전부가 폴백 사슬**이다. 두 매체가 다른 마크업을 쓰고 같은 매체 안에서도
 * 기사 종류마다 달라서, 한 단계가 안 맞으면 다음이 받아 주도록 짜여 있다.
 *
 * 그게 곧 위험이다 — **깨져도 예외가 안 난다.** 매체가 클래스 이름을 바꾸면 작성자가 전부
 * "Unknown"이 되고 발행일이 전부 "지금"으로 찍히는데, 로그에는 아무것도 안 남고 기사는 계속
 * 쌓인다. 화면이 최신순으로 정렬하니 순서만 조용히 무너진다.
 *
 * 그래서 여기서는 **각 폴백 단계가 실제로 동작하는지**를 한 단계씩 확인한다.
 */
class ScraperParsingTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-08-26T21:00+09:00")

    private fun doc(html: String, baseUri: String) = Jsoup.parse(html.trimIndent(), baseUri)

    // ── 시각 파싱 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO 시각을 서울 시간대로 옮긴다")
    fun testParseIsoToKst() {
        assertEquals(
            OffsetDateTime.parse("2026-08-26T21:00+09:00"),
            parseToKst("2026-08-26T12:00:00Z")
        )
    }

    @Test
    @DisplayName("epoch 초로 오는 매체도 읽는다")
    fun testParseEpochSeconds() {
        // 매체가 형식을 섞어 쓴다. 이 폴백이 빠지면 그 매체 기사가 전부 "지금"으로 찍힌다
        assertEquals(
            OffsetDateTime.parse("2026-08-26T21:00+09:00"),
            parseToKst("1787745600")
        )
    }

    @Test
    @DisplayName("못 읽는 값은 null — 호출부가 다음 후보로 넘어간다")
    fun testParseUnreadable() {
        assertNull(parseToKst(null))
        assertNull(parseToKst(""))
        assertNull(parseToKst("어제"))
    }

    // ── 상세 페이지 폴백 사슬 ────────────────────────────────────────────

    @Test
    @DisplayName("발행일은 published → modified → date → time 순으로 찾는다")
    fun testPublishedAtFallbackOrder() {
        // published_time이 있으면 그걸 쓴다
        val withPublished = doc(
            """
            <html><head>
              <meta property="article:published_time" content="2026-08-20T12:00:00Z">
              <meta property="article:modified_time" content="2026-08-25T12:00:00Z">
            </head><body></body></html>
            """,
            "https://as.com/x-n/"
        )
        assertEquals(
            OffsetDateTime.parse("2026-08-20T21:00+09:00"),
            parseArticleDetail(withPublished, now).publishedAt,
            "published_time이 있는데 modified_time이 이겼다"
        )

        // published_time이 없으면 modified_time으로 내려간다
        val onlyModified = doc(
            """
            <html><head>
              <meta property="article:modified_time" content="2026-08-25T12:00:00Z">
            </head><body></body></html>
            """,
            "https://as.com/x-n/"
        )
        assertEquals(
            OffsetDateTime.parse("2026-08-25T21:00+09:00"),
            parseArticleDetail(onlyModified, now).publishedAt
        )
    }

    @Test
    @DisplayName("time 태그의 datetime까지 내려간다")
    fun testPublishedAtFromTimeTag() {
        val html = doc(
            """<html><body><time datetime="2026-08-24T12:00:00Z">어제</time></body></html>""",
            "https://www.marca.com/x.html"
        )
        assertEquals(
            OffsetDateTime.parse("2026-08-24T21:00+09:00"),
            parseArticleDetail(html, now).publishedAt
        )
    }

    @Test
    @DisplayName("아무 데서도 못 찾으면 지금으로 둔다")
    fun testPublishedAtFallsBackToNow() {
        // 이 상태가 계속되면 기사가 전부 같은 시각으로 쌓여 정렬이 무너진다.
        // 예외는 안 나므로 이 테스트가 아니면 알 방법이 없다
        val html = doc("""<html><body><p>본문</p></body></html>""", "https://as.com/x-n/")
        assertEquals(now, parseArticleDetail(html, now).publishedAt)
    }

    @Test
    @DisplayName("작성자는 셀렉터 → 메타 → Unknown 순으로 찾는다")
    fun testAuthorFallbackChain() {
        val bySelector = doc(
            """<html><body><span class="ue-c-article__author-name">Javier Ortiz</span></body></html>""",
            "https://www.marca.com/x.html"
        )
        assertEquals("Javier Ortiz", parseArticleDetail(bySelector, now).authorName)

        val byMeta = doc(
            """<html><head><meta property="article:author" content="Ana Ruiz"></head><body></body></html>""",
            "https://as.com/x-n/"
        )
        assertEquals("Ana Ruiz", parseArticleDetail(byMeta, now).authorName)

        val neither = doc("""<html><body><p>본문</p></body></html>""", "https://as.com/x-n/")
        assertEquals("Unknown", parseArticleDetail(neither, now).authorName)
    }

    @Test
    @DisplayName("알려진 본문 셀렉터가 있으면 그것만 쓴다")
    fun testBodyFromKnownSelector() {
        val html = doc(
            """
            <html><body>
              <p>내비게이션 문구</p>
              <div class="ue-c-article__body"><p>첫 문단</p><p>둘째 문단</p></div>
              <p>관련 기사</p>
            </body></html>
            """,
            "https://www.marca.com/x.html"
        )

        val content = parseArticleDetail(html, now).content
        assertEquals("첫 문단\n\n둘째 문단", content, "본문 밖 문단이 섞여 들어왔다")
    }

    @Test
    @DisplayName("본문 셀렉터가 안 맞으면 앞쪽 문단 10개로 근사한다")
    fun testBodyFallbackLimitsParagraphs() {
        // 개수를 제한하지 않으면 꼬리의 관련기사·약관까지 본문이 된다
        val paragraphs = (1..15).joinToString("") { "<p>문단 $it</p>" }
        val html = doc("""<html><body>$paragraphs</body></html>""", "https://as.com/x-n/")

        val content = parseArticleDetail(html, now).content
        assertEquals(10, content.split("\n\n").size)
        assertTrue(content.startsWith("문단 1"))
        assertTrue(!content.contains("문단 11"))
    }

    @Test
    @DisplayName("대표 이미지는 og:image → twitter:image 순, 없으면 null")
    fun testImageFallback() {
        val og = doc(
            """<html><head><meta property="og:image" content="https://img/og.jpg">
               <meta name="twitter:image" content="https://img/tw.jpg"></head><body></body></html>""",
            "https://as.com/x-n/"
        )
        assertEquals("https://img/og.jpg", parseArticleDetail(og, now).imageUrl)

        val twitterOnly = doc(
            """<html><head><meta name="twitter:image" content="https://img/tw.jpg"></head><body></body></html>""",
            "https://as.com/x-n/"
        )
        assertEquals("https://img/tw.jpg", parseArticleDetail(twitterOnly, now).imageUrl)

        val none = doc("""<html><body></body></html>""", "https://as.com/x-n/")
        assertNull(parseArticleDetail(none, now).imageUrl)
    }

    // ── 목록 페이지 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("마르카: 외부 도메인 링크를 버린다")
    fun testMarcaDropsForeignLinks() {
        val html = doc(
            """
            <html><body>
              <article class="ue-c-cover-content">
                <h2>시메오네 인터뷰</h2><a href="https://www.marca.com/futbol/atletico/1.html"></a>
              </article>
              <article class="ue-c-cover-content">
                <h2>광고</h2><a href="https://ad.example.com/x"></a>
              </article>
            </body></html>
            """,
            "https://www.marca.com/futbol/atletico.html"
        )

        val result = parseMarcaList(html)
        assertEquals(1, result.size)
        assertEquals("시메오네 인터뷰", result[0].title)
    }

    @Test
    @DisplayName("마르카: 제목이 없는 항목은 버린다")
    fun testMarcaDropsTitleless() {
        val html = doc(
            """
            <html><body>
              <article class="ue-c-cover-content"><a href="https://www.marca.com/x.html"></a></article>
            </body></html>
            """,
            "https://www.marca.com/futbol/atletico.html"
        )
        assertTrue(parseMarcaList(html).isEmpty())
    }

    @Test
    @DisplayName("AS: 기사 URL만 남기고 섹션 링크는 버린다")
    fun testAsKeepsOnlyArticles() {
        // AS는 기사 URL을 -n(뉴스) -v(영상) -f(포토)로 끝낸다.
        // 섹션·태그 링크를 안 거르면 기사가 아닌 페이지를 본문 추출까지 돌려 시간을 버린다
        val html = doc(
            """
            <html><body><div class="b_col_a">
              <div class="s"><div class="s_t"><a href="https://as.com/futbol/simeone-n/">뉴스</a></div></div>
              <div class="s"><div class="s_t"><a href="https://as.com/futbol/highlight-v/">영상</a></div></div>
              <div class="s"><div class="s_t"><a href="https://as.com/futbol/photos-f">포토</a></div></div>
              <div class="s"><div class="s_t"><a href="https://as.com/noticias/atletico-madrid/">섹션</a></div></div>
              <div class="s"><div class="s_t"><a href="https://elpais.com/x-n/">외부</a></div></div>
            </div></body></html>
            """,
            "https://as.com/noticias/atletico-madrid/"
        )

        val result = parseAsList(html)
        assertEquals(listOf("뉴스", "영상", "포토"), result.map { it.title })
    }

    @Test
    @DisplayName("AS: 상대 경로 링크도 절대 URL로 바꿔 판정한다")
    fun testAsResolvesRelativeLinks() {
        // abs:href가 baseUri로 절대 경로를 만든다. 이게 없으면 startsWith 검사가 전부 실패한다
        val html = doc(
            """
            <html><body><div class="b_col_a">
              <div class="s"><div class="s_t"><a href="/futbol/atletico/x-n/">상대경로 기사</a></div></div>
            </div></body></html>
            """,
            "https://as.com/noticias/atletico-madrid/"
        )

        val result = parseAsList(html)
        assertEquals(1, result.size)
        assertEquals("https://as.com/futbol/atletico/x-n/", result[0].url)
    }
}
