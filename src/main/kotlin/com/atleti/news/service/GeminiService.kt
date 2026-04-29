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
    @Value("\${gemini.api.key}") private val rawApiKey: String,
    private val objectMapper: ObjectMapper
) {
    private val apiKey = rawApiKey.trim()
    private val webClient = WebClient.builder().build()

    suspend fun processArticle(title: String, fullContent: String, sourceName: String): GeminiResponse? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            println("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }
        
        // 키가 제대로 로드되었는지 로그로 확인 (앞 4자리, 뒤 4자리)
        val maskedKey = if (apiKey.length > 10) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "****"
        println("DEBUG: 현재 사용 중인 API Key: $maskedKey")

        return callGeminiApi(title, fullContent, sourceName)
    }

    private suspend fun callGeminiApi(title: String, fullContent: String, sourceName: String, retryCount: Int = 0): GeminiResponse? {
        // 유료 티어는 RPM이 넉넉하므로 지연 시간을 1초로 단축합니다.
        if (retryCount == 0) delay(1000)

        val prompt = """
            You are a professional translator for Atletico Madrid news.
            Analyze the following article and respond ONLY in valid JSON.
            
            [Source]
            $sourceName
            
            [Title]
            $title
            
            [Full Content]
            $fullContent
            
            JSON structure:
            {
              "is_atm_related": true,
              "reason": "summary of relation",
              "title_ko": "translated title",
              "content_ko": "full body translation in Korean (keep paragraphs)",
              "summary_ko": "Strictly 3 bullet points in Korean. format: 1. ...\n2. ...\n3. ..."
            }
        """.trimIndent()

        return try {
            val requestBody = mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt))))
            )

            // 비용 효율이 가장 뛰어난 Gemini 2.0 Flash Lite 모델을 사용합니다.
            val url = "https://generativelanguage.googleapis.com/v1/models/gemini-2.0-flash-lite-preview-02-05:generateContent?key=$apiKey"
            
            webClient.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
                .let { parseGeminiResponse(it) }
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 429 && retryCount < 2) {
                val backoff = (retryCount + 1) * 10000L
                println("⚠️ [Gemini 429] 할당량 초과. ${backoff/1000}초 후 재시도 (${retryCount + 1}/2)...")
                delay(backoff)
                return callGeminiApi(title, fullContent, sourceName, retryCount + 1)
            }
            
            println("❌ Gemini API HTTP 에러 (${e.statusCode}): ${e.responseBodyAsString}")
            if (e.statusCode.value() == 404) {
                println("💡 Tip: 404 에러는 모델 이름이나 API 버전이 잘못되었을 때 발생합니다. gemini-flash-latest 버전을 확인하세요.")
            }
            null
        } catch (e: Exception) {
            println("❌ Gemini 일반 에러: ${e.message}")
            null
        }
    }

    private fun parseGeminiResponse(response: Map<String, Any>): GeminiResponse? {
        return try {
            val candidates = response["candidates"] as? List<*> ?: return null
            val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
            val content = firstCandidate["content"] as? Map<*, *> ?: return null
            val parts = content["parts"] as? List<*> ?: return null
            val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
            val text = (firstPart["text"] as? String)?.trim() ?: return null
            
            val cleanJson = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            objectMapper.readValue<GeminiResponse>(cleanJson)
        } catch (e: Exception) {
            println("❌ JSON 파싱 실패: ${e.message}")
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
