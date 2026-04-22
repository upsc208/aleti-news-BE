package com.atleti.news.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class ScraperService {

    suspend fun scrapeMarcaAtm(): List<ScrapedArticle> = withContext(Dispatchers.IO) {
        val url = "https://www.marca.com/futbol/atletico.html"
        val doc = Jsoup.connect(url).get()
        
        doc.select("article").mapNotNull { element ->
            try {
                val title = element.select("h2, h3").text()
                val link = element.select("a").attr("abs:href")
                val imageUrl = element.select("img").attr("src")
                
                if (title.isNotBlank() && link.isNotBlank()) {
                    ScrapedArticle(
                        title = title,
                        url = link,
                        imageUrl = imageUrl,
                        sourceName = "Marca"
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class ScrapedArticle(
    val title: String,
    val url: String,
    val imageUrl: String?,
    val sourceName: String
)
