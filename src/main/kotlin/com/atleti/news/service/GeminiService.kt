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

    init {
        if (apiKey != "NO_KEY" && apiKey.isNotBlank()) {
            val maskedKey = if (apiKey.length > 10) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "****"
            println("✅ GeminiService 로드 완료. API Key: $maskedKey")
        }
    }

    suspend fun processArticle(title: String, fullContent: String, sourceName: String, skipValidation: Boolean = false): GeminiResponse? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            println("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }
        
        return callGeminiApi(title, fullContent, sourceName, skipValidation = skipValidation)
    }

    private suspend fun callGeminiApi(title: String, fullContent: String, sourceName: String, retryCount: Int = 0, skipValidation: Boolean = false): GeminiResponse? {
        // 유료 티어는 RPM이 넉넉하므로 지연 시간을 1초로 단축합니다.
        if (retryCount == 0) delay(1000)

        val validationInstruction = if (skipValidation) {
            "1. 'is_atm_related': Always return true (this source is already verified)."
        } else {
            "1. 'is_atm_related': Whether the content is about Atletico Madrid (players, coach, transfer, match, etc)."
        }

        val prompt = """
            You are a professional translator and journalist for Atletico Madrid news.
            Analyze the following content and respond ONLY in valid JSON.
            
            CRITICAL RULES:
            1. ONLY use the information provided in the [Content] section. 
            2. DO NOT use your internal knowledge to invent details (like players who left the team years ago or players from other leagues) if they are not in the text.
            3. If the [Content] is empty, very short, or entirely unrelated to Atletico Madrid, set "is_atm_related" to false and leave other fields empty.
            
            [Source]
            $sourceName
            
            [Title]
            $title
            
            [Content]
            $fullContent
            
            Instructions:
            $validationInstruction
            2. 'category': Choose one of: [이적시장], [경기리뷰], [오피셜], [부상/명단], [인터뷰], [예상라인업], [기타].
            3. 'title_ko': 
               - If it's a web article (Marca/AS), translate the original title.
               - If it's an SNS post (Twitter/X), CREATE a new concise one-line headline in Korean summarizing the content.
            4. 'content_ko': Full translation in Korean.
            5. 'summary_ko': 
               - If it's a web article, provide strictly 3 bullet points AS A SINGLE STRING separated by newlines (\n).
               - If it's an SNS post, leave this as null or an empty string.
            
            JSON structure (All values must be strings or null):
            {
              "is_atm_related": true,
              "category": "[이적시장]",
              "reason": "summary of relation",
              "title_ko": "translated or created title",
              "content_ko": "full translation",
              "summary_ko": "bullet point 1\nbullet point 2\nbullet point 3"
            }
        """.trimIndent()

        return try {
            val requestBody = mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt))))
            )

            // 비용 효율이 가장 뛰어난 Gemini 2.5 Flash Lite 모델을 사용합니다.
            val url = "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-lite:generateContent?key=$apiKey"
            
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
    val category: String? = "[기타]",
    val reason: String? = null,
    val title_ko: String?,
    val content_ko: String?,
    val summary_ko: String?
)
