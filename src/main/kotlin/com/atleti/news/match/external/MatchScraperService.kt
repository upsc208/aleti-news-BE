package com.atleti.news.match.external

import org.slf4j.LoggerFactory
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.domain.MatchResultRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** FotMob 스크랩이 실패했음을 알린다. "일정이 0건"과 구분하기 위해 필요하다. */
class FotMobScrapeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Service
class MatchScraperService(
    private val objectMapper: ObjectMapper,
    private val matchResultRepository: MatchResultRepository,
    private val transactionTemplate: TransactionTemplate,
    // 기본값은 실제 FotMob. 로컬 리허설에서만 가짜 서버로 돌린다 (scripts/fake-live-match.js)
    @Value("\${atleti.source.fotmob-base-url}") private val fotmobBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(MatchScraperService::class.java)

    private val FOTMOB_URL = "$fotmobBaseUrl/teams/$ATLETI_TEAM_ID/overview/atletico-madrid"

    suspend fun scrapeAndSaveLatestMatch(): MatchResult? = withContext(Dispatchers.IO) {
        try {
            val lastMatchNode = fetchOverviewNode().at("/lastMatch")
            if (!lastMatchNode.isMissingNode) {
                return@withContext saveLastMatch(lastMatchNode)
            }
            null
        } catch (e: Exception) {
            log.error("❌ [FotMob Scrape] 실패: ${e.message}", e)
            null
        }
    }

    /**
     * 다가올 경기를 킥오프 순으로 [limit]개 가져온다. (저장은 호출자 몫)
     *
     * 팀 페이지 자체가 아틀레티코 1군 일정이라, RapidAPI 검색과 달리
     * 여자팀·유스팀·동명이팀(미네이로 등)을 걸러낼 필요가 없다.
     *
     * @throws FotMobScrapeException 페이지 접속·파싱에 실패한 경우
     */
    suspend fun scrapeUpcomingMatches(limit: Int = 3): List<UpcomingMatch> = withContext(Dispatchers.IO) {
        val fixtures = fetchOverviewNode().at("/overviewFixtures")
        if (!fixtures.isArray) throw FotMobScrapeException("overviewFixtures 노드를 찾지 못했습니다")

        selectUpcoming(fixtures, Instant.now(), limit)
    }

    /**
     * 팀 페이지의 __NEXT_DATA__에서 overview 노드를 찾는다.
     * 최근 경기(lastMatch)와 전체 일정(overviewFixtures)이 모두 이 노드 아래에 있다.
     */
    private suspend fun fetchOverviewNode(): JsonNode {
        var lastError: Exception? = null

        // FotMob이 간헐적으로 403/타임아웃을 내므로 1회 재시도한다
        for (retry in 1..2) {
            try {
                val response = Jsoup.connect(FOTMOB_URL)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,ko;q=0.8")
                    .referrer("https://www.fotmob.com/")
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .maxBodySize(0)
                    .timeout(30000)
                    .execute()

                if (response.statusCode() != 200) {
                    lastError = FotMobScrapeException("HTTP ${response.statusCode()}")
                    log.warn("⚠️ [FotMob] HTTP ${response.statusCode()} - 재시도 $retry/2")
                    delay(2000)
                    continue
                }

                val jsonData = response.parse().select("#__NEXT_DATA__").first()?.data()
                    ?: throw FotMobScrapeException("__NEXT_DATA__ 스크립트를 찾지 못했습니다")
                val pageProps = objectMapper.readTree(jsonData).at("/props/pageProps")

                var overviewNode = pageProps.at("/fallback/team-$ATLETI_TEAM_ID/overview")
                if (overviewNode.isMissingNode) {
                    val fallback = pageProps.at("/fallback")
                    val dataKey = fallback.fieldNames().asSequence().find { it.contains("api/team") }
                    if (dataKey != null) overviewNode = fallback.at("/$dataKey/overview")
                }
                if (overviewNode.isMissingNode) overviewNode = pageProps.at("/overview/overview")
                if (overviewNode.isMissingNode) overviewNode = pageProps.at("/initialData/overview")

                if (overviewNode.isMissingNode) throw FotMobScrapeException("overview 노드를 찾지 못했습니다")
                return overviewNode
            } catch (e: FotMobScrapeException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log.warn("⚠️ [FotMob] 접속 실패 (재시도 $retry/2): ${e.message}", e)
                delay(2000)
            }
        }
        throw FotMobScrapeException("FotMob 페이지를 가져오지 못했습니다: ${lastError?.message}", lastError)
    }

    /**
     * 최근 경기를 저장한다. **바깥과 말하는 일, 조립, 저장을 각각 다른 곳에 둔다** —
     * 통계 조회는 여기서(I/O), 노드→엔티티 변환은 [parseLastMatch]에서(순수), 쓰기는 트랜잭션 안에서.
     */
    private suspend fun saveLastMatch(lastMatch: JsonNode): MatchResult? {
        val matchId = lastMatch.at("/id").asText()
        val stats = fetchMatchStats(matchId)
        val matchResult = parseLastMatch(lastMatch, stats)

        log.info("✅ [FotMob Scrape] 데이터 저장 시도: ${matchResult.homeTeam} vs ${matchResult.awayTeam} (${matchResult.matchDate})")

        // 같은 경기를 지우고 새로 넣는다. 한 트랜잭션으로 묶지 않으면 중간 실패 시
        // **최근 경기 기록이 통째로 사라진 상태**가 남는다 — 화면의 직전 경기 카드가 빈다.
        // suspend 함수에 @Transactional을 붙여도 withContext가 스레드를 옮겨 경계가 안 생긴다
        // (근거: SuspendTransactionTest). 그래서 명시적으로 경계를 친다.
        return transactionTemplate.execute {
            matchResultRepository.findByMatchId(matchId)?.let {
                matchResultRepository.delete(it)
                matchResultRepository.flush()
            }
            matchResultRepository.save(matchResult)
        }
    }

    /**
     * 경기 상세 페이지를 받아 온다. **네트워크만 담당하고 해석은 [parseMatchStats]에 맡긴다** —
     * 그래야 저장해 둔 응답으로 파싱을 검증할 수 있다 (`NaverSportsClient.parseGame`과 같은 이유).
     *
     * 상세 정보는 부가 데이터라 실패해도 빈 맵으로 넘어간다. 경기 결과 자체는 저장돼야 한다.
     */
    private suspend fun fetchMatchStats(matchId: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect("$fotmobBaseUrl/match/$matchId")
                .userAgent(USER_AGENT)
                .get()
            val jsonData = doc.select("#__NEXT_DATA__").first()?.data()
                ?: return@withContext emptyMap()
            parseMatchStats(objectMapper.readTree(jsonData).at("/props/pageProps/content"), objectMapper)
        } catch (e: Exception) {
            log.warn("⚠️ [FotMob Details] 상세 정보 추출 실패: ${e.message}", e)
            emptyMap()
        }
    }

    companion object {
        internal const val ATLETI_TEAM_ID = "9906"
        internal val KST: ZoneId = ZoneId.of("Asia/Seoul")
        internal const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * 다가올 경기를 골라 킥오프 순으로 [limit]개.
         *
         * 끝났거나 취소된 경기, [now]보다 과거인 경기를 버린다. 킥오프를 못 읽은 경기도 버린다 —
         * `ChatService`가 방 오픈 시각을 계산할 수 없어 어차피 쓸모없다.
         */
        internal fun selectUpcoming(fixtures: JsonNode, now: Instant, limit: Int): List<UpcomingMatch> =
            fixtures.asSequence()
                .filter { !it.at("/status/finished").asBoolean() && !it.at("/status/cancelled").asBoolean() }
                .mapNotNull { node ->
                    val kickoff = parseKickoff(node.at("/status/utcTime").asText()) ?: return@mapNotNull null
                    if (kickoff.isBefore(now)) null else node to kickoff
                }
                .sortedBy { (_, kickoff) -> kickoff }
                .take(limit)
                .map { (node, kickoff) -> toUpcomingEntity(node, kickoff) }
                .toList()

        internal fun toUpcomingEntity(node: JsonNode, kickoff: Instant): UpcomingMatch {
            // 홈/원정은 팀 이름이 아니라 FotMob 팀 ID로 판별한다.
            // 상대에 Real Madrid·Rayo Vallecano처럼 madrid가 들어가는 팀이 있어 문자열 비교는 뒤집힌다.
            val isHome = node.at("/home/id").asText() == ATLETI_TEAM_ID
            val opponent = node.at("/opponent/name").asText()
                .ifBlank { if (isHome) node.at("/away/name").asText() else node.at("/home/name").asText() }

            return UpcomingMatch(
                matchId = node.at("/id").asText().takeIf { it.isNotBlank() },
                opponent = opponent,
                // ChatService.findKickoffTime이 파싱하는 형식과 맞춰야 한다 (ISO_OFFSET_DATE_TIME / KST)
                matchDate = kickoff.atZone(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                isHome = isHome,
                tournament = node.at("/tournament/name").asText().takeIf { it.isNotBlank() } ?: "Tournament"
            )
        }

        internal fun parseKickoff(raw: String): Instant? =
            runCatching { Instant.parse(raw) }
                .recoverCatching { Instant.parse(raw.replace(" ", "T")) }
                .getOrNull()

        /**
         * 최근 경기 노드 + 상세 통계 → 저장할 엔티티. **순수 함수다** (I/O 없음).
         *
         * 홈/원정을 팀 이름으로 판정하는데, 여기는 `lastMatch` 노드라 팀 ID가 없다.
         * 시각을 못 읽으면 원문을 그대로 둔다 — 지어내는 것보다 낫다.
         */
        internal fun parseLastMatch(lastMatch: JsonNode, stats: Map<String, String>): MatchResult {
            val homeTeam = lastMatch.at("/home/name").asText()
            val utcTimeStr = lastMatch.at("/status/utcTime").asText()
            val kstTimeStr = runCatching {
                ZonedDateTime.parse(utcTimeStr).withZoneSameInstant(KST)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }.getOrDefault(utcTimeStr)

            return MatchResult(
                matchId = lastMatch.at("/id").asText(),
                homeTeam = homeTeam,
                awayTeam = lastMatch.at("/away/name").asText(),
                homeScore = lastMatch.at("/home/score").asInt(),
                awayScore = lastMatch.at("/away/score").asInt(),
                matchDate = kstTimeStr,
                tournament = lastMatch.at("/tournament/name").asText(),
                isHome = homeTeam.contains("Atletico", ignoreCase = true) ||
                    homeTeam.contains("Atlético", ignoreCase = true),
                scorers = stats["scorers"], xg = stats["xg"],
                possession = stats["possession"], shotsOnTarget = stats["shotsOnTarget"],
                cards = stats["cards"], lineup = stats["lineup"],
                substitutions = stats["substitutions"],
                homeFormation = stats["homeFormation"], awayFormation = stats["awayFormation"]
            )
        }

        /** 경기 상세 페이지의 `content` 노드 → 저장용 통계 맵. **순수 함수다** */
        internal fun parseMatchStats(content: JsonNode, objectMapper: ObjectMapper): Map<String, String> {
            val statsMap = mutableMapOf<String, String>()
            val shirtMap = mutableMapOf<String, String>()
            val lineup = content.at("/lineup")
            if (!lineup.isMissingNode) {
                listOf("homeTeam", "awayTeam").forEach { team ->
                    lineup.at("/$team/starters").elements().forEach { p -> shirtMap[p.at("/id").asText()] = p.at("/shirtNumber").asText() }
                    lineup.at("/$team/subs").elements().forEach { p -> shirtMap[p.at("/id").asText()] = p.at("/shirtNumber").asText() }
                }
                statsMap["homeFormation"] = lineup.at("/homeTeam/formation").asText()
                statsMap["awayFormation"] = lineup.at("/awayTeam/formation").asText()
            }

            val topStats = content.at("/stats/Periods/All/stats")
            val structuredStats = mutableMapOf<String, Map<String, String>>()
            if (topStats.isArray) {
                for (statGroup in topStats) {
                    for (stat in statGroup.at("/stats")) {
                        val title = stat.at("/title").asText()
                        val key = when (title) {
                            "Ball possession" -> "possession"
                            "Expected goals (xG)" -> "xg"
                            "Shots on target" -> "shotsOnTarget"
                            "Total shots" -> "totalShots"
                            "Corners" -> "corners"
                            "Accurate passes" -> "passAccuracy"
                            else -> null
                        }
                        if (key != null) {
                            structuredStats[key] = mapOf("home" to stat.at("/stats/0").asText(), "away" to stat.at("/stats/1").asText())
                        }
                    }
                }
            }
            
            val events = content.at("/matchFacts/events/events")
            val scorersStructured = mutableListOf<Map<String, Any?>>()
            val cardsStructured = mutableListOf<Map<String, Any?>>()
            val subsStructured = mutableListOf<Map<String, Any?>>()
            
            if (events.isArray) {
                for (event in events) {
                    val type = event.at("/type").asText()
                    val time = event.at("/time").asText()
                    val name = event.at("/player/name").asText().ifBlank { event.at("/nameStr").asText() }
                    val isHomeEvent = event.at("/isHome").asBoolean()
                    
                    when (type) {
                        "Goal" -> {
                            scorersStructured.add(mapOf(
                                "name" to name, "time" to time, 
                                "assist" to event.at("/assistInput").asText().ifBlank { null },
                                "isHome" to isHomeEvent
                            ))
                        }
                        "Card" -> {
                            val color = if (event.at("/card").asText().contains("Yellow")) "Y" else "R"
                            cardsStructured.add(mapOf("name" to name, "time" to time, "color" to color, "isHome" to isHomeEvent))
                        }
                        "Substitution" -> {
                            subsStructured.add(mapOf(
                                "time" to time,
                                "outName" to event.at("/swap/1/name").asText(),
                                "inName" to event.at("/swap/0/name").asText(),
                                "isHome" to isHomeEvent
                            ))
                        }
                    }
                }
            }
            
            statsMap["scorers"] = objectMapper.writeValueAsString(scorersStructured)
            statsMap["cards"] = objectMapper.writeValueAsString(cardsStructured)
            statsMap["substitutions"] = objectMapper.writeValueAsString(subsStructured)
            statsMap["xg"] = objectMapper.writeValueAsString(structuredStats)

            if (!lineup.isMissingNode) {
                val lineupStructured = mapOf(
                    "home" to mapOf(
                        "formation" to statsMap["homeFormation"],
                        "starters" to lineup.at("/homeTeam/starters").elements().asSequence().map { 
                            mapOf("name" to it.at("/name").asText(), "shirt" to it.at("/shirtNumber").asText())
                        }.toList()
                    ),
                    "away" to mapOf(
                        "formation" to statsMap["awayFormation"],
                        "starters" to lineup.at("/awayTeam/starters").elements().asSequence().map { 
                            mapOf("name" to it.at("/name").asText(), "shirt" to it.at("/shirtNumber").asText())
                        }.toList()
                    )
                )
                statsMap["lineup"] = objectMapper.writeValueAsString(lineupStructured)
            }

            return statsMap
        }
    }
}
