package com.atleti.news.service

import com.atleti.news.repository.CollectionLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class TwitterApiService(
    private val collectionLogRepository: CollectionLogRepository,
    @Value("\${rapidapi.key}") private val apiKey: String,
    @Value("\${rapidapi.twitter.host}") private val apiHost: String,
    @Value("\${rapidapi.daily.limit}") private val dailyLimit: Int
) {
    private val httpClient = HttpClient.newBuilder().build()

    // 오늘 사용한 API 호출 횟수 확인
    fun getTodayApiUsage(): Int {
        val startOfDay = OffsetDateTime.of(LocalDate.now(), LocalTime.MIN, ZoneOffset.UTC)
        return collectionLogRepository.sumTwitterApiCallCountSince(startOfDay)?.toInt() ?: 0
    }

    suspend fun getTweets(username: String): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val currentUsage = getTodayApiUsage()
        if (currentUsage >= dailyLimit) {
            println("⚠️ [RapidAPI] 일일 호출 제한($dailyLimit)에 도달하여 호출을 중단합니다. (현재 사용: $currentUsage)")
            return@withContext emptyList()
        }

        // alexanderxbx/twitter-api45의 타임라인 엔드포인트
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://$apiHost/timeline.php?screenname=$username"))
            .header("X-RapidAPI-Key", apiKey)
            .header("X-RapidAPI-Host", apiHost)
            .method("GET", HttpRequest.BodyPublishers.noBody())
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                val responseBody = response.body()
                println("DEBUG [RapidAPI] Response Body: ${responseBody.take(200)}...")
                val articles = parseTweets(responseBody, username)
                println("✅ [RapidAPI] @$username 트윗 수집 완료: ${articles.size}개 발견")
                articles
            } else {
                println("❌ [RapidAPI] 오류 발생: ${response.statusCode()} - ${response.body()}")
                emptyList()
            }
        } catch (e: Exception) {
            println("❌ [RapidAPI] 호출 중 예외 발생: ${e.message}")
            emptyList()
        }
    }

    private fun parseTweets(jsonBody: String, username: String): List<ScrapedArticle> {
        val tweets = try {
            val json = JSONObject(jsonBody)
            json.optJSONArray("timeline") 
                ?: json.optJSONArray("results") 
                ?: json.optJSONArray("tweets")
                ?: if (jsonBody.trim().startsWith("[")) org.json.JSONArray(jsonBody) else return emptyList()
        } catch (e: Exception) {
            try {
                org.json.JSONArray(jsonBody)
            } catch (e2: Exception) {
                println("⚠️ [RapidAPI] JSON 파싱 실패: \${e2.message}")
                return emptyList()
            }
        }
        
        val articles = mutableListOf<ScrapedArticle>()
        for (i in 0 until (tweets as org.json.JSONArray).length()) {
            val tweet = tweets.getJSONObject(i)
            val text = tweet.optString("text") ?: tweet.optString("full_text") ?: ""
            
            // 다양한 ID 필드 확인
            val tweetId = tweet.optString("tweet_id")
                .ifBlank { tweet.optString("id_str") }
                .ifBlank { tweet.optString("id") }
            
            // 발행 시간 추출
            val createdAtStr = tweet.optString("created_at")
            val publishedAt = try {
                // Twitter 날짜 형식: "Mon Apr 27 14:55:01 +0000 2026"
                val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", java.util.Locale.ENGLISH)
                OffsetDateTime.parse(createdAtStr, formatter)
            } catch (e: Exception) {
                OffsetDateTime.now()
            }
            
            if (text.isNotBlank() && tweetId.isNotBlank()) {
                val fullUrl = "https://x.com/$username/status/$tweetId"
                println("DEBUG [Twitter Scraper] Found Tweet: $fullUrl | Time: $publishedAt")
                articles.add(
                    ScrapedArticle(
                        title = text,
                        url = fullUrl,
                        imageUrl = null,
                        sourceName = username,
                        articleType = com.atleti.news.entity.ArticleType.SNS_POST,
                        authorName = username,
                        publishedAt = publishedAt
                    )
                )
            }
        }
        return articles
    }
}
