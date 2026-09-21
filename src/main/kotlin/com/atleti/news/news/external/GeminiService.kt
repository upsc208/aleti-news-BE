package com.atleti.news.news.external

import org.slf4j.LoggerFactory
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody

/* 기사와 유튜브 자막을 Gemini를 써 한국어 기사로 만드는 곳*/
@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val rawApiKey: String,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(GeminiService::class.java)

    private val apiKey = rawApiKey.trim()
    private val webClient = WebClient.builder().build()

    suspend fun processArticle(title: String, fullContent: String, sourceName: String, skipValidation: Boolean = false): GeminiResponse? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            log.warn("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }

        return callGeminiApi(title, fullContent, sourceName, skipValidation = skipValidation)
    }

    // 유튜브 라이브: 자막을 구간으로 나눠 요약하고, 합친 결과로 제목과 요약을 만듦
    suspend fun processYoutubeTranscript(transcript: String, sourceName: String, spainDate: String): GeminiResponse? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            log.warn("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }

        val chunks = TranscriptChunker.split(TranscriptChunker.stripThemeSong(transcript))
        if (chunks.isEmpty()) return null
        log.info("🎙️ [$sourceName] 자막 ${transcript.length}자 → ${chunks.size}개 구간으로 나눠 번역")

        val translations = mutableListOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            val translated = translateChunk(chunk, index, chunks.size) ?: return null
            if (translated.isEmpty() || translated == NOISE_ONLY) continue
            translations += translated
        }

        if (translations.isEmpty()) {
            log.info("🔇 [$sourceName] 번역할 내용이 없음 (잡음만 있는 방송)")
            return GeminiResponse(isAtmRelated = false, titleKo = null, contentKo = null, summaryKo = null)
        }

        val merged = RepetitionGuard.dropRepeatedParagraphs(translations.joinToString(PARAGRAPH_BREAK))
        val contentKo = merged.text
        val meta = requestText(buildYoutubeSummaryPrompt(contentKo, spainDate), "유튜브 제목·요약")
            ?.let { parseJson<YoutubeSummary>(it) }
            ?: return null

        return GeminiResponse(
            isAtmRelated = meta.isAtmRelated,
            category = "라이브요약",
            titleKo = "Ruben Uria) $spainDate 라이브 내용('${meta.topic.orEmpty().trim()}')",
            summaryKo = meta.summaryKo,
            contentKo = contentKo,
        )
    }

    // 구간 하나를 번역한다. 되풀이에 빠지면 한 번 더
    private suspend fun translateChunk(chunk: String, index: Int, total: Int): String? {
        val label = "유튜브 구간 ${index + 1}/$total"
        val prompt = buildChunkTranslationPrompt(chunk, index, total)

        val first = attempt(prompt, label, DEFAULT_TEMPERATURE) ?: return null
        if (first.clean) return first.text

        log.warn("⚠️ [$label] ${first.problem} — 온도를 올려 다시 시도합니다.")
        val second = attempt(prompt, "$label 재시도", LOOP_RETRY_TEMPERATURE) ?: return first.text
        if (!second.clean) log.warn("⚠️ [$label] 다시 시도해도 남았습니다: ${second.problem}")

        val betterSecond = second.text.isNotBlank() && (second.clean || second.text.length > first.text.length)
        return if (betterSecond) second.text else first.text
    }

    // 축구와 관련 없으면 걸러야함
    private suspend fun attempt(prompt: String, label: String, temperature: Double): Attempt? {
        val raw = requestText(prompt, label, temperature = temperature) ?: return null
        val guarded = guard(cleanChunkText(raw))

        val onTopic = OffTopicFilter.clean(guarded.text)
        if (onTopic.dropped.isNotEmpty()) {
            log.info("🧹 [$label] 광고·다른 종목 문단 ${onTopic.dropped.size}개를 뺐습니다: ${onTopic.dropped}")
        }

        val problem = guarded.repeatedUnit?.let { "'$it' 반복" }
        return Attempt(onTopic.text, problem)
    }

    private data class Attempt(val text: String, val problem: String?) {
        val clean: Boolean get() = problem == null
    }

    private fun guard(raw: String): RepetitionGuard.Result {
        val trimmed = RepetitionGuard.trimLoop(raw)
        val deduped = RepetitionGuard.dropRepeatedParagraphs(trimmed.text)
        return RepetitionGuard.Result(deduped.text, trimmed.repeatedUnit ?: deduped.repeatedUnit)
    }

    private fun cleanChunkText(raw: String): String =
        raw.trim().removePrefix("```").removeSuffix("```").trim()

    private fun buildChunkTranslationPrompt(chunk: String, index: Int, total: Int): String {
        return """
            You are a Korean sports editor working from a Spanish YouTube live show about Atletico Madrid.
            The show is a panel discussion: the host (Ruben Uria) and several panelists take turns talking.
            This is segment ${index + 1} of $total of an automatically generated transcript. Condense it into readable Korean.

            KEEP (translate faithfully, close to the original wording)
            - Anything about Atletico Madrid: the squad, the coach, the board, matches, injuries, line-ups, transfers, the youth team.
            - Football elsewhere too: other clubs, other leagues, national teams, referees, the transfer market in general.
            - Concrete facts: names, clubs, numbers, dates, scores, fees, contract terms, and what a speaker predicts or claims.
            - **Who says what.** This is a debate. When speakers disagree, keep both sides.

            CUT
            - Greetings, goodbyes, sponsor reads, subscribe/like requests, reading out viewer names or donations, jokes, banter.
            - Filler and repetition: "eh", "bueno", false starts, and the same point made three times — keep it once.

            LENGTH
            - This segment is ${chunk.length} characters of Spanish. Write about $TARGET_CHARS characters of Korean, $MAX_CHARS at the very most.
            - Group the segment into 1-3 topics, not more. Merge related points into the same topic instead of splitting them.
            - Each topic gets 4-8 sentences. A two-line topic means you split something that belongs together.

            NEVER
            - Never repeat a sentence you already wrote. If you find yourself writing the same claim again, stop and move on.
            - Never number a topic label to continue it ([OO 2], [OO 3]). One label, one topic, written once.
            - Do not add a fact, name, club, number, or opinion that is not in the segment. No background knowledge about squads or transfers.
            - Do not turn a passing mention into a transfer story, and do not merge separate statements into a new claim.
            - Do not add an introduction or a conclusion, markdown headings (#), bold (**), or bullet lists.

            OUTPUT
            - Plain Korean text only. No JSON, no markdown.
            - Keep the order in which things were said. Separate topics with a blank line.
            - Open each topic with two brackets: a tag, then a short label taken from what was actually said.
              The tag is ${OffTopicFilter.FOOTBALL_LABEL} when the topic is football, ${OffTopicFilter.OFF_TOPIC_LABEL} when it is not (a film, another sport, politics, the hosts' private lives).
              Every topic carries one of the two — e.g. ${OffTopicFilter.FOOTBALL_LABEL}[오사수나전 리뷰], ${OffTopicFilter.OFF_TOPIC_LABEL}[영화 홍보].
              The label itself is 12 Korean characters or fewer, with no numbers appended.
            - Inside a topic, write it as the discussion it is. Name the speaker when the transcript makes it clear:
              "루벤: …", "패널: …", "호르헤: …". When it is not clear who is speaking, write "한 패널은 …라고 본다".
            - Every sentence ends in written plain style "~다" (평어체): ~했다, ~이다, ~라고 본다, ~할 것이다, questions as ~인가/~는가.
              NEVER use polite endings (~습니다, ~요, ~죠) and NEVER use casual endings (~야, ~어, ~해, ~지).
              This applies to quoted speech too. Speakers talk politely on air, but write what they said in plain style:
              "월드컵 일정 때문에 불이익을 받았습니다" → "패널: 월드컵 일정 때문에 불이익을 받았다."
            - If the segment has no football content at all, output exactly: $NOISE_ONLY

            Spanish names: initial J → ㅎ (Juan → 후안), LL → ㅇ/ㄹ (Llorente → 요렌테), G before e/i → ㅎ (Gimenez → 히메네스). Do not use English phonetics.
            Nicknames: "Cholo" → 촐로, "Atleti" → 아틀레티.

            [Segment]
            $chunk
        """.trimIndent()
    }

    private fun buildYoutubeSummaryPrompt(contentKo: String, spainDate: String): String = """
        Below is a Korean translation of a Spanish football YouTube live show ($spainDate). Respond ONLY in valid JSON.

        Use ONLY what is written in the translation. Do not add names, facts, or context from background knowledge.

        Fields:
        - "is_atm_related": true if the show discusses Atletico Madrid at all, otherwise false.
        - "topic": the single most discussed topic, 15 Korean characters or fewer, naming the concrete subject (a match, a player, a transfer) as written in the translation (e.g. "OO전 승리", "OO 이적설"). Generic labels like "경기 분석" are not allowed.
        - "summary_ko": 3-5 sentences recapping the most-discussed points, separated by "\n\n". Polite style (~했습니다, ~입니다).
          Each sentence must carry a concrete fact from the translation: who, which match, what result or claim.
          No vague filler such as "분석을 공유했습니다", "논의가 이루어졌습니다", "소통하는 시간을 가졌습니다".
          If two different matches are discussed, keep them distinct — do not attach a fact about one match to another.

        [Translation]
        $contentKo

        JSON:
        {"is_atm_related": true, "topic": "...", "summary_ko": "..."}
    """.trimIndent()

    // Gemini 호출. 429와 5xx는 기다렸다 다시 시도
    private suspend fun requestText(
        prompt: String,
        label: String,
        retryCount: Int = 0,
        temperature: Double = DEFAULT_TEMPERATURE,
    ): String? {
        return try {
            val requestBody = mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
                "generationConfig" to mapOf(
                    "temperature" to temperature,
                    "maxOutputTokens" to MAX_OUTPUT_TOKENS,
                ),
            )
            val url = "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-lite:generateContent?key=$apiKey"

            val response = webClient.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
            extractText(response) ?: run {
                log.warn("⚠️ [$label] Gemini 응답에 본문이 없습니다: ${response.keys}")
                null
            }
        } catch (e: WebClientResponseException) {
            val status = e.statusCode.value()
            if (status == 429 && retryCount < 2) {
                val backoff = (retryCount + 1) * 10000L
                log.warn("⚠️ [$label][Gemini 429] 할당량 초과. ${backoff / 1000}초 대기 후 재시도 (${retryCount + 1}/2)...")
                delay(backoff)
                return requestText(prompt, label, retryCount + 1, temperature)
            } else if ((status == 503 || status == 500) && retryCount < 3) {
                val backoff = (retryCount + 1) * 2000L
                log.warn("⚠️ [$label][Gemini $status] 서버 바쁨. ${backoff / 1000}초 대기 후 재시도 (${retryCount + 1}/3)...")
                delay(backoff)
                return requestText(prompt, label, retryCount + 1, temperature)
            }
            log.error("❌ [$label] Gemini API HTTP 에러 ($status): ${e.responseBodyAsString}", e)
            null
        } catch (e: Exception) {
            log.error("❌ [$label] Gemini 오류: ${e.message}", e)
            null
        }
    }

    private fun extractText(response: Map<String, Any>): String? {
        val candidates = response["candidates"] as? List<*> ?: return null
        val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
        val content = firstCandidate["content"] as? Map<*, *> ?: return null
        val parts = content["parts"] as? List<*> ?: return null
        return parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            .joinToString("")
            .ifBlank { null }
    }

    private inline fun <reified T> parseJson(text: String): T? = try {
        val cleanJson = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .replace(Regex("\\\\(?![u\"\\\\/bfnrt])"), "")
        objectMapper.readValue<T>(cleanJson)
    } catch (e: Exception) {
        log.warn("⚠️ JSON 파싱 실패: ${e.message}", e)
        null
    }

    internal data class YoutubeSummary(
        @JsonProperty("is_atm_related") val isAtmRelated: Boolean = false,
        @JsonProperty("topic") val topic: String? = null,
        @JsonProperty("summary_ko") val summaryKo: String? = null,
    )

    companion object {
        private const val NOISE_ONLY = "(없음)"

        private val PARAGRAPH_BREAK = "${Char(10)}${Char(10)}"

        private const val DEFAULT_TEMPERATURE = 0.3

        private const val LOOP_RETRY_TEMPERATURE = 0.9

        private const val MAX_OUTPUT_TOKENS = 16384

        private const val TARGET_CHARS = 950
        private const val MAX_CHARS = 1300
    }

    suspend fun generateHeadlines(newsLines: List<String>, existingHeadlines: List<String>): List<String>? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            log.warn("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
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
            log.error("❌ Gemini 헤드라인 생성 오류: ${e.message}", e)
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
            log.warn("⚠️ 헤드라인 JSON 파싱 실패: ${e.message}", e)
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
                log.warn("⚠️ [Gemini 429] 할당량 초과. ${backoff/1000}초 대기 후 재시도 (${retryCount + 1}/2)...")
                delay(backoff)
                return callGeminiApi(title, fullContent, sourceName, retryCount + 1, skipValidation)
            } else if ((e.statusCode.value() == 503 || e.statusCode.value() == 500) && retryCount < 3) {
                val backoff = (retryCount + 1) * 2000L
                log.warn("⚠️ [Gemini ${e.statusCode.value()}] 서버 바쁨. ${backoff/1000}초 대기 후 재시도 (${retryCount + 1}/3)...", e)
                delay(backoff)
                return callGeminiApi(title, fullContent, sourceName, retryCount + 1, skipValidation)
            }

            log.error("❌ Gemini API HTTP 에러 (${e.statusCode}): ${e.responseBodyAsString}", e)
            null
        } catch (e: Exception) {
            log.error("❌ Gemini 오류: ${e.message}", e)
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
            log.warn("⚠️ JSON 파싱 실패: ${e.message}", e)
            null
        }
    }
}
