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
        // twitter-api45의 응답은 보통 최상위가 배열이거나 'timeline' 키를 가집니다.
        val tweets = try {
            val json = JSONObject(jsonBody)
            json.optJSONArray("timeline") ?: return emptyList()
        } catch (e: Exception) {
            // 응답이 배열로 직접 올 경우 처리
            try {
                org.json.JSONArray(jsonBody)
            } catch (e2: Exception) {
                return emptyList()
            }
        }
        
        val articles = mutableListOf<ScrapedArticle>()
        for (i in 0 until tweets.length()) {
            val tweet = tweets.getJSONObject(i)
            val text = tweet.optString("text") ?: ""
            // twitter-api45는 tweet_id 대신 id_str을 쓰기도 합니다.
            val tweetId = tweet.optString("tweet_id").ifBlank { tweet.optString("id_str") }
            
            if (text.isNotBlank()) {
                articles.add(
                    ScrapedArticle(
                        title = text.take(100) + if (text.length > 100) "..." else "",
                        url = "https://x.com/$username/status/$tweetId",
                        imageUrl = null,
                        sourceName = "X (@$username)"
                    )
                )
            }
        }
        return articles
    }
}
