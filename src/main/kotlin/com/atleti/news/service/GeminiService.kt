package com.atleti.news.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody

@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val apiKey: String,
    private val objectMapper: ObjectMapper
) {
    private val webClient = WebClient.builder().build()

    suspend fun processArticle(title: String, sourceName: String): GeminiResponse? {
        if (apiKey.isBlank()) {
            println("경고: GEMINI_API_KEY가 설정되지 않았습니다. AI 판별을 건너뜁니다.")
            return null
        }

        // 429 방지를 위한 약간의 지연 (무료 티어 배려)
        delay(2000)

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

            // 2026년 기준 gemini-flash-latest가 가장 안정적
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey"

            webClient.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
                .let { parseGeminiResponse(it) }
        } catch (e: WebClientResponseException) {
            println("Gemini API 호출 실패 (HTTP ${e.statusCode}): ${e.responseBodyAsString}")
            null
        } catch (e: Exception) {
            println("Gemini API 호출 실패: ${e.message}")
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

        val cleanJson = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            // ObjectMapper(이미 스프링에 의해 KotlinModule이 등록됨)를 사용하여 파싱
            objectMapper.readValue<GeminiResponse>(cleanJson)
        } catch (e: Exception) {
            println("JSON 파싱 에러: ${e.message} (원문: $text)")
            null
        }
    }
}

data class GeminiResponse(
    val is_atm_related: Boolean,
    val title_ko: String?,
    val summary_ko: String?
)
