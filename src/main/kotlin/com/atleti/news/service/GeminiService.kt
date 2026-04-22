package com.atleti.news.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val apiKey: String,
    private val objectMapper: ObjectMapper
) {
    private val webClient = WebClient.builder()
        .baseUrl("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent")
        .build()

    suspend fun processArticle(title: String, sourceName: String): GeminiResponse? {
        if (apiKey.isBlank()) return null

        val prompt = """
            당신은 아틀레티코 마드리드(Atletico Madrid) 전문 기자입니다. 
            다음 뉴스 제목이 아틀레티코 마드리드 구단과 직접적으로 관련된 뉴스인지 판별하고, 관련이 있다면 한국어로 3줄 요약해주세요.
            
            뉴스 제목: $title
            출처: $sourceName
            
            응답 형식(JSON):
            {
              "is_atm_related": true/false,
              "title_ko": "뉴스 제목 번역",
              "summary_ko": "1. 첫 번째 요약\n2. 두 번째 요약\n3. 세 번째 요약"
            }
            관련이 없다면 is_atm_related를 false로 설정하고 나머지 필드는 비워두세요.
        """.trimIndent()

        return try {
            val requestBody = mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to prompt)
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json"
                )
            )

            webClient.post()
                .uri { it.queryParam("key", apiKey).build() }
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
                .let { parseGeminiResponse(it) }
        } catch (e: Exception) {
            println("Gemini API 호출 실패: \${e.message}")
            null
        }
    }

    private fun parseGeminiResponse(response: Map<String, Any>): GeminiResponse? {
        val candidates = response["candidates"] as? List<*> ?: return null
        val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
        val content = firstCandidate["content"] as? Map<*, *> ?: return null
        val parts = content["parts"] as? List<*> ?: return null
        val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
        val text = firstPart["text"] as? String ?: return null
        
        return try {
            // ObjectMapper(이미 스프링에 의해 KotlinModule이 등록됨)를 사용하여 파싱
            objectMapper.readValue<GeminiResponse>(text)
        } catch (e: Exception) {
            println("JSON 파싱 에러: \${e.message}")
            null
        }
    }
}

data class GeminiResponse(
    val is_atm_related: Boolean,
    val title_ko: String?,
    val summary_ko: String?
)
