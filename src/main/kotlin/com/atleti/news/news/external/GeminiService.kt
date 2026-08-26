package com.atleti.news.news.external

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
            1. Filter out only pure "noise": greetings, sponsor mentions, channel promotion, chat small talk, and non-football jokes.
            2. EVERYTHING ELSE must be covered. Do NOT compress the stream into a few highlights — translate ALL substantive football content (players, coach, transfers, match analysis, panel discussions, exclusive info), following the chronological flow of the stream.
            3. If the content is empty or unrelated to Atletico Madrid, set "is_atm_related" to false.

            [Faithful Translation - MOST IMPORTANT]
            This is a STRICT literal translation task (직독직해). You are a translator, NOT an analyst or reporter.
            1. Translate ONLY what is literally said in the [Transcript Content]. Do NOT add, infer, embellish, or "improve" anything.
            2. NEVER use your own background knowledge to invent facts. Do NOT decide who is on the team, who left, or who is being signed. Just translate the words as they are.
            3. If a player is only mentioned in passing, translate exactly that — do NOT turn a passing mention into a "transfer target" or "signing" story that the speaker never claimed.
            4. Do NOT connect unrelated sentences into a fabricated narrative. Keep each statement's original meaning and scope.
            5. If something is ambiguous or you are unsure, translate it plainly and literally rather than guessing an interpretation.
            6. Absolutely no hallucination. It is far better to translate something dryly than to invent a claim the speaker did not make.

            [Speech Style - CRITICAL]
            - 'content_ko': Write as a DIRECT TRANSLATION of what Ruben Uria actually says, as if he is speaking. Sentence endings MUST use Korean plain style (평어체): "~했다", "~이다", "~라고 본다", "~어떤가", "~준비했다". NEVER use formal endings here. Keep his first-person voice and rhetorical questions (e.g., "알마다의 사우디 이적설 관련 내용을 준비했다", "패널분들의 의견은 어떤가").
            - 'summary_ko': This is the AI's own recap for the reader, NOT his direct speech. Use polite formal endings (존댓말: "~했습니다", "~입니다", "~전망입니다").

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
            4. 'summary_ko': Provide a 3-5 line concise summary. Use clear paragraph breaks (\n\n) between logical points for readability. Do not merge sentences into a single block. Use polite formal endings (존댓말: ~했습니다, ~입니다) — this is the AI's recap, not his direct speech.
            5. 'content_ko': Provide a thorough, near-full translation of the stream in Korean (NOT just highlights).
               - Cover ALL substantive football content in the order it was said. Only skip pure noise (greetings, sponsors, chat small talk, jokes). When in doubt, include it.
               - CRITICAL: DO NOT use '#' headers or excessive bolding ('**').
               - Use clean list markers like '-', '•', or emojis/icons.
               - Use brackets (e.g., [이적 시장], [선수 분석]) to categorize sections.
               - Ensure wide spacing between paragraphs so it's easy to read on mobile.
               - Convey what Ruben Uria and the panel actually said, in his speaking voice. This should be the longest part.
               - CRITICAL: End sentences in plain style as if directly transcribing his words (평어체: ~했다, ~준비했다, ~라고 본다, ~어떤가). NEVER use "~했습니다/~입니다".

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

    // 여러 최신 뉴스를 종합해 헤드라인 0~2개를 생성 (뉴스 헤드라인/속보 형태)
    suspend fun generateHeadlines(newsLines: List<String>, existingHeadlines: List<String>): List<String>? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            println("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }
        if (newsLines.isEmpty()) return emptyList()

        val newsBlock = newsLines.joinToString("\n")
        val existingBlock = if (existingHeadlines.isEmpty()) "(none)" else existingHeadlines.joinToString("\n")

        val prompt = """
            You are the front-page editor for an Atletico Madrid news feed.
            Below are the LATEST news items collected since the previous briefing.
            From them, select the MOST IMPORTANT stories and write AT MOST 4 short Korean headlines (fewer is fine).

            RULES:
            1. These headlines are a live "breaking news" ticker. Cover only the most important / newest developments — ignore minor or repetitive items.
            2. If nothing is genuinely important or newsworthy, return an EMPTY list. Do NOT invent or pad headlines just to fill space. It is perfectly fine to return zero headlines. Only include an item if it is genuinely worth a headline — do not add weak items just to reach 4.
            3. Return between 0 and 4 headlines, chosen strictly by importance. List the MOST important first.
            4. [Importance priority] Atletico fans care most about TRANSFERS. Rank items in this priority order:
               (a) Confirmed official signings or departures (영입/방출 오피셜, 공식발표).
               (b) Transfer news or rumors (이적설), especially when reported by a well-known, credible journalist/outlet (e.g., Fabrizio Romano, Marca, AS). Weigh the [출처] when judging credibility.
               (c) Other major club news (injuries, lineups, match results, interviews).
               Prefer transfer-related items over non-transfer items when choosing what to include.
            5. Each headline: informative Korean newspaper style, target 30-50 characters (max 50). Long enough to carry WHO + WHAT with a concrete detail (club, fee, source, status). Avoid one-word or overly terse headlines. NO ending punctuation.
            6. Use plain style endings (평어체) or noun-ending style, and include a concrete detail. e.g., "훌리안 알바레스, 사우디 클럽의 대형 이적 제안 공개 거부", "시메오네 감독, 2028년까지 재계약 최종 합의 임박".
            7. DO NOT repeat or paraphrase any of the [Already Posted Headlines]. Produce genuinely new angles only.

            [Strict Grounding - MOST IMPORTANT]
            8. You are RE-HEADLINING existing news items, NOT writing new stories. Every headline MUST be directly and fully supported by ONE specific item in [Latest News].
            9. Do NOT change the factual meaning of an item. Never turn a goal, injury, quote, or match report into a transfer/signing story (or vice versa).
            10. NEVER use outside or background knowledge. Do NOT assume who currently plays for which club, who is a transfer target, or any squad detail not stated in the item itself. Example of a FORBIDDEN hallucination: an item mentions a CURRENT Atletico player, and you invent that Atletico is "trying to sign him" — that is completely wrong. Only restate what the item actually says.
            11. Do NOT merge multiple items into a single fabricated narrative. One headline reflects one item's actual content.
            12. If you cannot write a headline that faithfully matches an item, DROP it. Fewer (or zero) accurate headlines is always better than one distorted or invented one.

            [Already Posted Headlines]
            $existingBlock

            [Latest News]
            $newsBlock

            Respond ONLY in valid JSON (headlines may be an empty array):
            {
              "headlines": ["headline 1", "headline 2"]
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
                .let { parseHeadlineResponse(it) }
        } catch (e: Exception) {
            println("❌ Gemini 헤드라인 생성 오류: ${e.message}")
            null
        }
    }

    private fun parseHeadlineResponse(response: Map<String, Any>): List<String>? {
        return try {
            val candidates = response["candidates"] as? List<*> ?: return null
            val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
            val content = firstCandidate["content"] as? Map<*, *> ?: return null
            val parts = content["parts"] as? List<*> ?: return null
            val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
            val text = (firstPart["text"] as? String)?.trim() ?: return null

            var cleanJson = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            cleanJson = cleanJson.replace(Regex("\\\\(?![u\"\\\\/bfnrt])"), "")

            objectMapper.readValue<HeadlineResponse>(cleanJson).headlines
        } catch (e: Exception) {
            println("⚠️ 헤드라인 JSON 파싱 실패: ${e.message}")
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
