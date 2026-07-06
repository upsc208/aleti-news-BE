package com.atleti.news.service

import com.fasterxml.jackson.annotation.JsonProperty
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

    suspend fun processArticle(title: String, fullContent: String, sourceName: String, skipValidation: Boolean = false): GeminiResponse? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            println("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }

        return callGeminiApi(title, fullContent, sourceName, skipValidation = skipValidation)
    }

    suspend fun processYoutubeTranscript(transcript: String, sourceName: String, spainDate: String): GeminiResponse? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            println("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }

        val prompt = """
            You are an expert Atletico Madrid journalist. 
            Analyze the following Spanish YouTube transcript and respond ONLY in valid JSON.

            CRITICAL RULES:
            1. Filter out all "noise" like greetings, sponsor mentions, chat readings, and non-football related jokes.
            2. Focus ONLY on news related to Atletico Madrid (players, coach, transfers, match analysis, exclusive info).
            3. If the content is empty or unrelated to Atletico Madrid, set "is_atm_related" to false.

            [Naming Convention - CRITICAL]
            Regardless of the source language (Spanish or English), ALWAYS use the following Korean names for these players/people:
            - Antoine Griezmann: 앙투안 그리즈만
            - Julián Álvarez: 훌리안 알바레스
            - Marc Pubill: 마르크 푸빌
            - Alexander Sørloth: 알렉산더 쇠를로트
            - Robin Le Normand: 로빈 르 노르망
            - Conor Gallagher: 코너 갤러거
            - David Hancko: 다비드 한츠코
            - Clement Lenglet: 클레망 랑글레
            - Juan Musso: 후안 무소
            - Diego Simeone: 디에고 시메오네
            - Mateu Alemany: 마테우 알레마니
            - Marcos Llorente: 마르코스 요렌테
            - Jan Oblak: 얀 오블락
            - Koke: 코케
            - Jose Maria Gimenez: 호세 마리아 히메네스
            - Pablo Barrios: 파블로 바리오스
            - Thiago Almada: 티아고 알마다
            - Johnny Cardoso: 조니 카르도소
            - Ademola Lookman: 아데몰라 룩먼
            - Giacomo Raspadori: 자코모 라스파도리
            - Álex Baena: 알렉스 바에나
            - Matteo Ruggeri: 마테오 루제리
            - Nicolás González: 니콜라스 곤살레스
            - Nahuel Molina: 나우엘 몰리나
            - Rodrigo Mendoza: 로드리고 멘도사
            - Obed Vargas: 오베드 바르가스
            - Giuliano Simeone: 줄리아노 시메오네
            - Miguel Llorente Cubo: 미켈 쿠보
            - Javi Boñar: 하비 보냐르
            - Javi Morcillo: 하비 모르시요
            - Rayane Belaid: 리야드 벨라이드
            - Adrián Niño: 아드리안 니뇨
            - Salim El Jebari: 살림 엘 제바리
            - Iker Luque: 이케르 루케
            - Diego Bri: 디에고 브리
            - Jorge Mota: 호르헤 모타
            - Abde Raihani: 압데 라이하니
            - Aitor Gismera: 아이토르 기스메라
            - Gerónimo Spina: 헤로니모 스피나
            - Ilias Kostis: 일리아스 코스티스

            General Spanish/English-to-Korean rules:
            1. 'J' at the beginning of names -> 'ㅎ' (e.g., Juan -> 후안, Julian -> 훌리안).
            2. 'LL' -> 'ㄹ' or 'y' sound (e.g., Llorente -> 요렌테, Villar -> 비야르).
            3. 'G' before 'e' or 'i' -> 'ㅎ' (e.g., Gimenez -> 히메네스).
            4. Do not use English-style phonetics for Spanish names (e.g., NOT '그리에이츠만', NOT '줄리안').

            [Transcript Content]
            $transcript

            Instructions:
            1. 'is_atm_related': true/false.
            2. 'category': Always return "라이브요약".
            3. 'title_ko': Create a title in the EXACT format: "Ruben Uria) $spainDate 라이브 내용('간략한 핵심 내용')".
               - Use the provided date: $spainDate.
               - '간략한 핵심 내용' MUST be extremely concise (Max 10-15 characters). Pick ONLY the single most important topic or two very short ones (e.g., "훌리안 이적설", "갤러거 협상 중"). Do not list everything.
            4. 'summary_ko': Provide a 3-5 line concise summary. Use clear paragraph breaks (\n\n) between logical points for readability. Do not merge sentences into a single block.
            5. 'content_ko': Provide a detailed "Core Report" in Korean. 
               - CRITICAL: DO NOT use '#' headers or excessive bolding ('**'). 
               - Use clean list markers like '-', '•', or emojis/icons.
               - Use brackets (e.g., [이적 시장], [선수 분석]) to categorize sections.
               - Ensure wide spacing between paragraphs so it's easy to read on mobile.
               - Elaborate on the key points, who said what, and why it matters. This should be the longest part.

            JSON structure:
            {
              "is_atm_related": true,
              "category": "라이브요약",
              "title_ko": "catchy title",
              "summary_ko": "concise summary",
              "content_ko": "detailed core report"
            }
        """.trimIndent()

        return try {
            val requestBody = mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt))))
            )

            val url = "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-lite:generateContent?key=$apiKey"

            webClient.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
                .let { parseGeminiResponse(it) }
        } catch (e: Exception) {
            println("❌ Gemini 유튜브 처리 오류: ${e.message}")
            null
        }
    }

    private suspend fun callGeminiApi(title: String, fullContent: String, sourceName: String, retryCount: Int = 0, skipValidation: Boolean = false): GeminiResponse? {
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

            [Naming Convention - CRITICAL]
            Regardless of the source language (Spanish or English), ALWAYS use the following Korean names for these players/people:
            - Antoine Griezmann: 앙투안 그리즈만
            - Julián Álvarez: 훌리안 알바레스
            - Marc Pubill: 마르크 푸빌
            - Alexander Sørloth: 알렉산더 쇠를로트
            - Robin Le Normand: 로빈 르 노르망
            - Conor Gallagher: 코너 갤러거
            - David Hancko: 다비드 한츠코
            - Clement Lenglet: 클레망 랑글레
            - Juan Musso: 후안 무소
            - Diego Simeone: 디에고 시메오네
            - Mateu Alemany: 마테우 알레마니
            - Marcos Llorente: 마르코스 요렌테
            - Jan Oblak: 얀 오블락
            - Koke: 코케
            - Jose Maria Gimenez: 호세 마리아 히메네스
            - Pablo Barrios: 파블로 바리오스
            - Thiago Almada: 티아고 알마다
            - Johnny Cardoso: 조니 카르도소
            - Ademola Lookman: 아데몰라 룩먼
            - Giacomo Raspadori: 자코모 라스파도리
            - Álex Baena: 알렉스 바에나
            - Matteo Ruggeri: 마테오 루제리
            - Nicolás González: 니콜라스 곤살레스
            - Nahuel Molina: 나우엘 몰리나
            - Rodrigo Mendoza: 로드리고 멘도사
            - Obed Vargas: 오베드 바르가스
            - Giuliano Simeone: 줄리아노 시메오네
            - Miguel Llorente Cubo: 미켈 쿠보
            - Javi Boñar: 하비 보냐르
            - Javi Morcillo: 하비 모르시요
            - Rayane Belaid: 리야드 벨라이드
            - Adrián Niño: 아드리안 니뇨
            - Salim El Jebari: 살림 엘 제바리
            - Iker Luque: 이케르 루케
            - Diego Bri: 디에고 브리
            - Jorge Mota: 호르헤 모타
            - Abde Raihani: 압데 라이하니
            - Aitor Gismera: 아이토르 기스메라
            - Gerónimo Spina: 헤로니모 스피나
            - Ilias Kostis: 일리아스 코스티스

            General Spanish/English-to-Korean rules:
            1. 'J' at the beginning of names -> 'ㅎ' (e.g., Juan -> 후안, Julian -> 훌리안).
            2. 'LL' -> 'ㄹ' or 'y' sound (e.g., Llorente -> 요렌테, Villar -> 비야르).
            3. 'G' before 'e' or 'i' -> 'ㅎ' (e.g., Gimenez -> 히메네스).
            4. Do not use English-style phonetics for Spanish names (e.g., NOT '그리에이츠만', NOT '줄리안').

            [Source]
            $sourceName

            [Title]
            $title

            [Content]
            $fullContent

            Instructions:
            $validationInstruction
            2. 'category': Choose one of: [이적설], [경기리포트], [공식발표], [부상/소집], [인터뷰], [훈련소식], [기타].
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
              "category": "[이적설]",
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
                println("⚠️ [Gemini 429] 할당량 초과. ${backoff/1000}초 대기 후 재시도 (${retryCount + 1}/2)...")
                delay(backoff)
                return callGeminiApi(title, fullContent, sourceName, retryCount + 1, skipValidation)
            } else if ((e.statusCode.value() == 503 || e.statusCode.value() == 500) && retryCount < 3) {
                val backoff = (retryCount + 1) * 2000L
                println("⚠️ [Gemini ${e.statusCode.value()}] 서버 바쁨. ${backoff/1000}초 대기 후 재시도 (${retryCount + 1}/3)...")
                delay(backoff)
                return callGeminiApi(title, fullContent, sourceName, retryCount + 1, skipValidation)
            }

            println("❌ Gemini API HTTP 에러 (${e.statusCode}): ${e.responseBodyAsString}")
            null
        } catch (e: Exception) {
            println("❌ Gemini 오류: ${e.message}")
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

            var cleanJson = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            cleanJson = cleanJson.replace(Regex("\\\\(?![u\"\\\\/bfnrt])"), "")

            objectMapper.readValue<GeminiResponse>(cleanJson)
        } catch (e: Exception) {
            println("⚠️ JSON 파싱 실패: ${e.message}")
            null
        }
    }
}
