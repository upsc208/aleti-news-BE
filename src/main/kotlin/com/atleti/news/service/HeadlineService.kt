package com.atleti.news.service

import com.atleti.news.entity.NewsHeadline
import com.atleti.news.repository.NewsArticleRepository
import com.atleti.news.repository.NewsHeadlineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class HeadlineService(
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val newsHeadlineRepository: NewsHeadlineRepository
) {
    companion object {
        private const val MAX_HEADLINES = 5          // DB에 유지할 최대 헤드라인 수 (롤링)
        private const val MAX_PER_RUN = 4            // 한 스케줄에 새로 올릴 최대 개수 (중요도 기준, 꼭 4개는 아님)
        private const val ARTICLE_FETCH_LIMIT = 50   // 한 번에 참고할 최신 기사 상한 (토큰 보호)
        private const val FALLBACK_HOURS = 6L        // 직전 헤드라인이 없을 때 되돌아볼 시간
        private const val MAX_LOOKBACK_HOURS = 24L   // 헤드라인이 오래 끊겨도 이보다 더 과거는 안 봄
    }

    suspend fun generateAndStore() = withContext(Dispatchers.IO) {
        val nowSeoul = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))

        // 1. 커서 계산: 직전 헤드라인 생성 시각 이후만. 없으면 최근 6시간, 너무 오래됐으면 24시간으로 제한.
        val lastHeadline = newsHeadlineRepository.findTopByOrderByCreatedAtDesc()
        val floor = nowSeoul.minusHours(MAX_LOOKBACK_HOURS)
        var since = lastHeadline?.createdAt ?: nowSeoul.minusHours(FALLBACK_HOURS)
        if (since.isBefore(floor)) since = floor

        // 2. 커서 이후 신규 ATM 기사 수집
        val articles = newsArticleRepository.findRecentForBriefing(since, PageRequest.of(0, ARTICLE_FETCH_LIMIT))
        val newsLines = articles.mapNotNull { a ->
            val title = a.titleKo?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null
            // 출처(기자/매체)는 공신력 판단 근거로 함께 제공
            val source = a.authorName?.trim()?.takeIf { it.isNotBlank() } ?: a.sourceName
            // 제목만으로는 모델이 맥락을 지어낼 수 있어, 있으면 요약을 함께 넣어 근거를 보강
            val summary = a.summaryKo?.trim()?.replace("\n", " ")?.take(200)
            if (summary.isNullOrBlank()) {
                "- [${a.category ?: "기타"}] (출처: $source) $title"
            } else {
                "- [${a.category ?: "기타"}] (출처: $source) $title (요약: $summary)"
            }
        }
        if (newsLines.isEmpty()) {
            println("📰 [헤드라인] 신규 기사 없음(since=$since) → 생성 생략")
            return@withContext
        }

        // 3. 기존 헤드라인은 중복 방지용으로 전달
        val existing = newsHeadlineRepository.findAllByOrderByCreatedAtDesc().map { it.content }

        val generated = geminiService.generateHeadlines(newsLines, existing)
        if (generated == null) {
            println("❌ [헤드라인] 생성 실패")
            return@withContext
        }

        // 중복/공백 제거. 중요한 게 없으면 generated 자체가 비어 있어 여기서 그대로 종료됨.
        val fresh = generated
            .map { it.trim() }
            .filter { it.isNotBlank() && existing.none { e -> e.equals(it, ignoreCase = true) } }
            .distinct()
            .take(MAX_PER_RUN)

        if (fresh.isEmpty()) {
            println("📰 [헤드라인] 중요한 신규 헤드라인 없음 → 추가 생략")
            return@withContext
        }

        // 4. 저장
        fresh.forEach { newsHeadlineRepository.save(NewsHeadline(content = it)) }
        println("✅ [헤드라인] ${fresh.size}개 등록: ${fresh.joinToString(" | ")}")

        // 5. 롤링: 최신 5개만 남기고 오래된 건 삭제
        val all = newsHeadlineRepository.findAllByOrderByCreatedAtDesc()
        if (all.size > MAX_HEADLINES) {
            val toDelete = all.drop(MAX_HEADLINES)
            newsHeadlineRepository.deleteAll(toDelete)
            println("🧹 [헤드라인] 오래된 ${toDelete.size}개 삭제 (최신 ${MAX_HEADLINES}개 유지)")
        }
    }
}
