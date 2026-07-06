package com.atleti.news.service

import com.fasterxml.jackson.annotation.JsonProperty

data class GeminiResponse(
    @JsonProperty("is_atm_related")
    val isAtmRelated: Boolean,

    @JsonProperty("category")
    val category: String? = "[기타]",

    @JsonProperty("reason")
    val reason: String? = null,

    @JsonProperty("title_ko")
    val titleKo: String?,

    @JsonProperty("content_ko")
    val contentKo: String?,

    @JsonProperty("summary_ko")
    val summaryKo: String?
)

// 여러 뉴스를 종합해 만든 헤드라인 목록 응답
data class HeadlineResponse(
    @JsonProperty("headlines")
    val headlines: List<String> = emptyList()
)
