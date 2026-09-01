package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DataInitializer(private val newsSourceRepository: NewsSourceRepository) {
    private val logger = LoggerFactory.getLogger(DataInitializer::class.java)

    @Bean
    fun initData() = CommandLineRunner {
        logger.info("🚀 Data initialization started...")
        val sources = listOf(
            NewsSource(
                internalId = "marca",
                displayName = "Marca (마르카)",
                sourceUrl = "https://www.marca.com/futbol/atletico.html",
                category = "MEDIA",
                iconUrl = "https://www.marca.com/favicon.ico"
            ),
            NewsSource(
                internalId = "as",
                displayName = "AS (아스)",
                sourceUrl = "https://as.com/noticias/atletico-madrid/",
                category = "MEDIA",
                iconUrl = "https://as.com/favicon.ico"
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
            ),
            NewsSource(
                internalId = "Dvinuesa",
                displayName = "다비드 비누에사 말박 (@Dvinuesa)",
                sourceUrl = "https://x.com/Dvinuesa",
                category = "JOURNALIST"
            ),
            NewsSource(
                internalId = "pascualruizar",
                displayName = "파스칼 루이스 아르날 (@pascualruizar)",
                sourceUrl = "https://x.com/pascualruizar",
                category = "JOURNALIST"
            ),
            NewsSource(
                internalId = "jsancris_",
                displayName = "호르헤 산 크리스토발 (@jsancris_)",
                sourceUrl = "https://x.com/jsancris_",
                category = "JOURNALIST"
            ),
            NewsSource(
                internalId = "Objetivoatleti",
                displayName = "오브헤티보 아틀레티 (@Objetivoatleti)",
                sourceUrl = "https://x.com/Objetivoatleti",
                category = "OFFICIAL"
            ),
            NewsSource(
                internalId = "atletico_stats_",
                displayName = "아틀레티코 스탯 (@atletico_stats_)",
                sourceUrl = "https://x.com/atletico_stats_",
                category = "OFFICIAL"
            )

        )

        sources.forEach { source ->
            val existing = newsSourceRepository.findByInternalId(source.internalId)
            if (existing == null) {
                newsSourceRepository.save(source)
                logger.info("✅ Saved new source: ${source.internalId}")
            } else {
                // 이미 존재한다면 isActive가 true인지 확인 (혹시 수동으로 꺼져있을 수 있으니 다시 활성화)
                if (!existing.isActive) {
                    existing.isActive = true
                    newsSourceRepository.save(existing)
                    logger.info("🔄 Updated existing source to active: ${existing.internalId}")
                } else {
                    logger.info("ℹ️ Source already exists and is active: ${existing.internalId}")
                }
            }
        }
        logger.info("🏁 Data initialization completed.")
    }
}
