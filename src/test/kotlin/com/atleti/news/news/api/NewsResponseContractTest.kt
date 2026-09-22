package com.atleti.news.news.api

import com.atleti.news.news.api.dto.CollectionLogResponse
import com.atleti.news.news.api.dto.NewsSourceResponse
import com.atleti.news.news.domain.CollectionLog
import com.atleti.news.news.domain.CollectionStatus
import com.atleti.news.news.domain.NewsSource
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/**
 * 엔티티 직접 노출을 응답 DTO로 끊을 때 **API 응답이 바뀌지 않았는지** 고정한다
 * (docs/architecture.md 1.2-③, 6절 2단계).
 *
 * 이 검증이 필요한 이유: 컴파일러는 JSON 필드 이름을 봐 주지 않는다. 특히 `isActive` 같은
 * `is` 접두사 프로퍼티는 Jackson이 Java 빈 규약으로 읽으면 `active`가 되고 Kotlin 모듈로
 * 읽으면 `isActive`가 되어, **어느 쪽인지 추측하면 프론트가 조용히 깨진다.**
 * 엔티티와 DTO를 같은 ObjectMapper로 직렬화해 문자열이 일치하는지 직접 대조한다.
 *
 * ObjectMapper는 [com.atleti.news.common.JacksonConfig]와 동일하게 구성한다 —
 * 설정이 달라지면 이 테스트가 지키는 것이 실제 응답과 달라진다.
 */
class NewsResponseContractTest {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    @DisplayName("수집 소스 — DTO가 엔티티와 같은 JSON을 만든다")
    fun testNewsSourceResponseKeepsShape() {
        val entity = NewsSource(
            id = 7L,
            internalId = "Marca",
            displayName = "마르카",
            sourceUrl = "https://www.marca.com/",
            iconUrl = "https://example.com/marca.png",
            category = "MEDIA",
            isActive = true
        )

        assertEquals(
            mapper.writeValueAsString(entity),
            mapper.writeValueAsString(NewsSourceResponse.of(entity)),
            "엔티티를 DTO로 바꾼 것만으로 응답이 달라지면 프론트가 깨진다"
        )
    }

    @Test
    @DisplayName("수집 소스 — null 필드도 같은 방식으로 나간다")
    fun testNewsSourceResponseKeepsNulls() {
        // sourceUrl·iconUrl이 없는 소스가 실제로 있다. null을 생략하는지 그대로 두는지가
        // 엔티티와 DTO에서 갈리면 그것도 계약 변경이다
        val entity = NewsSource(
            id = null,
            internalId = "MatteoMoretto",
            displayName = "마테오 모레토",
            sourceUrl = null,
            iconUrl = null,
            category = "JOURNALIST",
            isActive = true
        )

        assertEquals(
            mapper.writeValueAsString(entity),
            mapper.writeValueAsString(NewsSourceResponse.of(entity))
        )
    }

    @Test
    @DisplayName("수집 로그 — DTO가 엔티티와 같은 JSON을 만든다")
    fun testCollectionLogResponseKeepsShape() {
        val entity = CollectionLog(
            id = 42L,
            status = CollectionStatus.SUCCESS,
            scannedCount = 30,
            aiProcessedCount = 12,
            twitterApiCallCount = 3,
            footballApiCallCount = 1,
            errorMsg = null,
            executionTimeMs = 8_421L,
            createdAt = OffsetDateTime.parse("2026-08-26T04:10:00+09:00")
        )

        assertEquals(
            mapper.writeValueAsString(entity),
            mapper.writeValueAsString(CollectionLogResponse.of(entity))
        )
    }

    @Test
    @DisplayName("수집 로그 — 실패 기록의 에러 메시지도 그대로 실린다")
    fun testCollectionLogResponseKeepsErrorMessage() {
        val entity = CollectionLog(
            id = 43L,
            status = CollectionStatus.FAIL,
            errorMsg = "Gemini 429 — 할당량 초과",
            executionTimeMs = 1_200L,
            createdAt = OffsetDateTime.parse("2026-08-26T04:12:00+09:00")
        )

        assertEquals(
            mapper.writeValueAsString(entity),
            mapper.writeValueAsString(CollectionLogResponse.of(entity))
        )
    }
}
