package com.atleti.news.service

import com.atleti.news.entity.MatchResult
import com.atleti.news.repository.MatchResultRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Service
class MatchScraperService(
    private val objectMapper: ObjectMapper,
    private val matchResultRepository: MatchResultRepository
) {
    private val ATLETI_TEAM_ID = "9906"
    private val FOTMOB_URL = "https://www.fotmob.com/teams/$ATLETI_TEAM_ID/overview/atletico-madrid"

    @Transactional
    suspend fun scrapeAndSaveLatestMatch(): MatchResult? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(FOTMOB_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .get()

            val jsonData = doc.select("#__NEXT_DATA__").first()?.data() ?: return@withContext null
            val root = objectMapper.readTree(jsonData)
            val pageProps = root.at("/props/pageProps")
            
            var overviewNode = pageProps.at("/fallback/team-$ATLETI_TEAM_ID/overview")
            if (overviewNode.isMissingNode) {
                val fallback = pageProps.at("/fallback")
                val dataKey = fallback.fieldNames().asSequence().find { it.contains("api/team") }
                if (dataKey != null) overviewNode = fallback.at("/$dataKey/overview")
            }
            if (overviewNode.isMissingNode) overviewNode = pageProps.at("/overview/overview")
            if (overviewNode.isMissingNode) overviewNode = pageProps.at("/initialData/overview")

            if (overviewNode.isMissingNode) return@withContext null

            val lastMatchNode = overviewNode.at("/lastMatch")
            if (!lastMatchNode.isMissingNode) {
                return@withContext saveLastMatch(lastMatchNode)
            }
            null
        } catch (e: Exception) {
            println("❌ [FotMob Scrape] 실패: ${e.message}")
            null
        }
    }

    private suspend fun saveLastMatch(lastMatch: JsonNode): MatchResult? {
        val matchId = lastMatch.at("/id").asText()
        val homeTeam = lastMatch.at("/home/name").asText()
        val awayTeam = lastMatch.at("/away/name").asText()
        val homeScore = lastMatch.at("/home/score").asInt()
        val awayScore = lastMatch.at("/away/score").asInt()
        val tournament = lastMatch.at("/tournament/name").asText()
        val isHome = homeTeam.contains("Atletico", ignoreCase = true) || homeTeam.contains("Atlético", ignoreCase = true)

        val utcTimeStr = lastMatch.at("/status/utcTime").asText()
        val kstTimeStr = try {
            val utcTime = ZonedDateTime.parse(utcTimeStr)
            val kstTime = utcTime.withZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))
            kstTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            utcTimeStr
        }

        val stats = fetchMatchStats(matchId)

        val matchResult = MatchResult(
            matchId = matchId, homeTeam = homeTeam, awayTeam = awayTeam,
            homeScore = homeScore, awayScore = awayScore,
            matchDate = kstTimeStr, tournament = tournament, isHome = isHome,
            scorers = stats["scorers"], xg = stats["xg"],
            possession = stats["possession"], shotsOnTarget = stats["shotsOnTarget"],
            cards = stats["cards"], lineup = stats["lineup"],
            substitutions = stats["substitutions"],
            homeFormation = stats["homeFormation"], awayFormation = stats["awayFormation"]
        )

        val existing = matchResultRepository.findByMatchId(matchId)
        if (existing != null) {
            matchResultRepository.delete(existing)
            matchResultRepository.flush()
        }

        println("✅ [FotMob Scrape] 데이터 저장 시도: $homeTeam vs $awayTeam ($kstTimeStr)")
        val saved = matchResultRepository.save(matchResult)
        matchResultRepository.flush()
        return saved
    }

    private suspend fun fetchMatchStats(matchId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val statsMap = mutableMapOf<String, String>()
        try {
            val matchUrl = "https://www.fotmob.com/match/$matchId"
            val doc = Jsoup.connect(matchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .get()
            
            val jsonData = doc.select("#__NEXT_DATA__").first()?.data() ?: return@withContext statsMap
            val root = objectMapper.readTree(jsonData)
            val content = root.at("/props/pageProps/content")
            
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
            
        } catch (e: Exception) {
            println("⚠️ [FotMob Details] 상세 정보 추출 실패: ${e.message}")
        }
        return@withContext statsMap
    }
}
