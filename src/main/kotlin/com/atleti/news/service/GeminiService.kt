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
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            println("⚠️ 경고: GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }

        // 429 방지를 위한 기본 지연
        delay(6000)

        return callGeminiApi(title, sourceName, retryCount = 0)
    }

    private suspend fun callGeminiApi(title: String, sourceName: String, retryCount: Int): GeminiResponse? {
        val isTwitter = sourceName.startsWith("X (@")
        
        val prompt = if (isTwitter) {
            """
                당신은 스페인 축구 전문 번역가입니다. 
                다음 트윗 내용은 외국어(영어 또는 스페인어)입니다. 이를 **반드시 한국어로 번역**하여 JSON 형식으로 응답하십시오.

                **수행 과제:**
                1. 'title_ko': 트윗 내용을 한 줄로 요약한 한국어 제목.
                2. 'content_ko': 트윗 전체 내용을 가감 없이 매끄러운 한국어로 번역한 전문.
                3. 'summary_ko': 트윗의 핵심 내용을 3개의 불렛포인트 한국어 문장으로 요약.

                트윗 내용: $title
                출처: $sourceName

                응답 형식(반드시 이 JSON 구조를 지키고 다른 설명은 하지 마세요):
                {
                  "is_atm_related": true,
                  "reason": "X 기사 자동 승인",
                  "title_ko": "한국어 요약 제목",
                  "content_ko": "한국어 번역 전문",
                  "summary_ko": "1. 요약1\n2. 요약2\n3. 요약3"
                }
            """.trimIndent()
        } else {
            """
                당신은 아틀레티코 마드리드 전문 기자입니다. 
                다음 뉴스가 구단과 관련 있는지 판별하고, 관련 있다면 한국어로 요약/번역하여 JSON으로 응답하십시오.
                
                뉴스 제목: $title
                출처: $sourceName

                응답 형식(JSON):
                {
                  "is_atm_related": true/false,
                  "reason": "판별 이유",
                  "title_ko": "한국어 번역 제목",
                  "content_ko": "한국어 번역 전문",
                  "summary_ko": "1. 요약1\n2. 요약2\n3. 요약3"
                }
            """.trimIndent()
        }

        return try {
            val requestBody = mapOf(
                "contents" to listOf(
                    mapOf("parts" to listOf(mapOf("text" to prompt)))
                )
                // 호환성 문제 해결을 위해 generationConfig 제거
            )

            val url = "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key=$apiKey"
            println("DEBUG: Gemini 호출 URL: ${url.replace(apiKey, "HIDDEN_KEY")}")

            webClient.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
                .let { 
                    val result = parseGeminiResponse(it)
                    if (result != null) {
                        println("✅ Gemini 번역 성공: ${result.title_ko}")
                    }
                    result
                }
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 429 && retryCount < 2) {
                println("⚠️ [Gemini 429] 쿼터 초과. 20초 대기 후 재시도합니다...")
                delay(20000)
                return callGeminiApi(title, sourceName, retryCount + 1)
            }
            println("❌ Gemini API HTTP 에러 (${e.statusCode}): ${e.responseBodyAsString}")
            null
        } catch (e: Exception) {
            println("❌ Gemini API 일반 에러: ${e.message}")
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
        
        // JSON 추출 로직 강화 (백틱 제거)
        val cleanJson = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            objectMapper.readValue<GeminiResponse>(cleanJson)
        } catch (e: Exception) {
            println("❌ JSON 파싱 에러: ${e.message} (원문: $text)")
            null
        }
    }
}

data class GeminiResponse(
    val is_atm_related: Boolean,
    val reason: String? = null,
    val title_ko: String?,
    val content_ko: String?,
    val summary_ko: String?
)
