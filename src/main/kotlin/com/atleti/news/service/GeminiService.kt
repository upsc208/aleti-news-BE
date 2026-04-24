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
            println("⚠️ 경고: GEMINI_API_KEY가 설정되지 않았습니다. (.env 파일이나 환경 변수를 확인하세요)")
            return null
        }

        // 429 방지를 위한 4초 지연 (분당 최대 15회 제한 준수)
        delay(4000)

        val prompt = """
            당신은 아틀레티코 마드리드(Atletico Madrid) 전문 기자입니다. 
            다음 뉴스 제목이 아틀레티코 마드리드 구단과 직접적으로 관련된 뉴스인지 판별하고, 관련이 있다면 한국어로 3줄 요약해주세요.
            
            **판별 지침:**
            1. 'Atleti', 'ATM', 'Rojiblancos', 'Colchoneros', 'Cholo', 'Metropolitano' 등을 포함합니다.
            2. 현재 아틀레티코 1군 선수단 및 유스 선수 관련 소식은 모두 포함합니다.
            3. **중요**: 출처(Source)가 'MercadoATM_', 'atletiuniverse' 등 아틀레티코 전문 매체인 경우, 제목에 구단명이 없더라도 해당 구단 선수에 대한 소식일 확률이 매우 높으므로 적극적으로 관련성을 인정하세요.
            
            뉴스 제목: $title
            출처: $sourceName
            
            응답 형식(JSON):
            {
              "is_atm_related": true/false,
              "title_ko": "뉴스 제목 번역",
              "summary_ko": "1. 첫 번째 요약\n2. 두 번째 요약\n3. 세 번째 요약"
            }
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

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey"

            webClient.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
                .let { 
                    val result = parseGeminiResponse(it)
                    if (result != null) {
                        println("🤖 AI 응답 결과: is_related=${result.is_atm_related}, title=${result.title_ko}")
                    }
                    result
                }
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
        
        println("DEBUG [Gemini Raw Response]: $text")

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
