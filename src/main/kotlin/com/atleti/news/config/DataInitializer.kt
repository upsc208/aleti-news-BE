package com.atleti.news.config

import com.atleti.news.entity.NewsSource
import com.atleti.news.repository.NewsSourceRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DataInitializer(private val newsSourceRepository: NewsSourceRepository) {

    @Bean
    fun initData() = CommandLineRunner {
        val sources = listOf(
            NewsSource(
                internalId = "Marca",
                displayName = "Marca (마르카)",
                sourceUrl = "https://www.marca.com/futbol/atletico.html",
                category = "MEDIA",
                iconUrl = "https://www.marca.com/favicon.ico"
            ),
            NewsSource(
                internalId = "MatteoMoretto",
                displayName = "마테오 모레토 (@MatteoMoretto)",
                sourceUrl = "https://x.com/MatteoMoretto",
                category = "JOURNALIST"
            ),
            NewsSource(
                internalId = "atletiuniverse",
                displayName = "아틀레티 유니버스 (@atletiuniverse)",
                sourceUrl = "https://x.com/atletiuniverse",
                category = "OFFICIAL"
            ),
            NewsSource(
                internalId = "RubenUria",
                displayName = "루벤 우리아 (@RubenUria)",
                sourceUrl = "https://x.com/RubenUria",
                category = "JOURNALIST"
            ),
            NewsSource(
                internalId = "PedroFullanaSER",
                displayName = "페드로 풀라나 (@PedroFullanaSER)",
                sourceUrl = "https://x.com/PedroFullanaSER",
                category = "JOURNALIST"
            ),
            NewsSource(
                internalId = "MercadoATM_",
                displayName = "메르카도 ATM (@MercadoATM_)",
                sourceUrl = "https://x.com/MercadoATM_",
                category = "OFFICIAL"
            ),
            NewsSource(
                internalId = "EfectoSime1",
                displayName = "에펙토 시메오네 (@EfectoSime1)",
                sourceUrl = "https://x.com/EfectoSime1",
                category = "OFFICIAL"
            ),
            NewsSource(
                internalId = "medinamarca",
                displayName = "다비드 메디나 (@medinamarca)",
                sourceUrl = "https://x.com/medinamarca",
                category = "JOURNALIST"
            )
        )

        sources.forEach { source ->
            if (newsSourceRepository.findByInternalId(source.internalId) == null) {
                newsSourceRepository.save(source)
            }
        }
    }
}
