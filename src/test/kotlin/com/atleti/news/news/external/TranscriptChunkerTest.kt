package com.atleti.news.news.external

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TranscriptChunkerTest {

    @Test
    fun `순서를 지키고 단어 중간에서 끊지 않는다`() {
        val transcript = (1..50).joinToString(" ") { "palabra$it" }

        val chunks = TranscriptChunker.split(transcript, maxChars = 60)

        assertThat(chunks).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(60) }
        assertThat(chunks.joinToString(" ")).isEqualTo(transcript)
    }

    @Test
    fun `방송 앞뒤 주제가 가사를 잘라 낸다`() {
        // 2026-09-15 라이브 자막 앞뒤 모양을 줄인 것
        val transcript = "tu historía las tertulias del atletis. Es el atletism, es nuestra pasión. En tu muría encontramos conexión. " +
            "Es el atleti, es nuestra pasión. En el día encontramos conexión. " +
            "Hola, hola, ¿qué tal familia? Ayer el Atlético ganó 0-3. Coke estuvo enorme. " +
            "Os dejo con el tío Juanchi, ¿vale? Adiós. Ser fel. teoría las tertulias llamas del atletis sin rumores. " +
            "Juan Chito va a contar una nueva oportunidad es el Athletic, es nuestra pasión. En el día encontramos conexión."

        assertThat(TranscriptChunker.stripThemeSong(transcript))
            .isEqualTo("Hola, hola, ¿qué tal familia? Ayer el Atlético ganó 0-3. Coke estuvo enorme. Os dejo con el tío Juanchi, ¿vale? Adiós. Ser fel.")
    }

    @Test
    fun `주제가가 없으면 자막을 그대로 둔다`() {
        val transcript = "Hola familia. Hoy hablamos del partido."
        assertThat(TranscriptChunker.stripThemeSong(transcript)).isEqualTo(transcript)
    }

    @Test
    fun `빈 자막은 구간이 없다`() {
        assertThat(TranscriptChunker.split("   ")).isEmpty()
    }
}
