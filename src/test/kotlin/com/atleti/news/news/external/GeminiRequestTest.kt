package com.atleti.news.news.external

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * 키가 URL이 아니라 헤더로 나가는지 고정한다.
 * 쿼리에 실으면 4xx 예외 메시지에 요청 URL이 통째로 들어가 로그에 키가 남는다.
 */
class GeminiRequestTest {

    private lateinit var server: HttpServer
    private var capturedUri: String = ""
    private var capturedKeyHeader: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `키를 헤더로 보내고 URL에는 남기지 않는다`() {
        val service = geminiService()

        runBlocking { service.generateHeadlines(listOf("- [이적설] 테스트 기사"), emptyList()) }

        assertThat(capturedKeyHeader).isEqualTo("secret-key")
        assertThat(capturedUri).doesNotContain("secret-key")
        assertThat(capturedUri).doesNotContain("key=")
    }

    @Test
    fun `설정한 모델 이름으로 호출한다`() {
        val service = geminiService()

        runBlocking { service.generateHeadlines(listOf("- [이적설] 테스트 기사"), emptyList()) }

        assertThat(capturedUri).contains("/v1/models/gemini-test-model:generateContent")
    }

    private fun geminiService() = GeminiService(
        rawApiKey = "secret-key",
        model = "gemini-test-model",
        baseUrl = "http://localhost:${server.address.port}",
        objectMapper = ObjectMapper().registerKotlinModule(),
    )

    private fun respond(exchange: HttpExchange) {
        capturedUri = exchange.requestURI.toString()
        capturedKeyHeader = exchange.requestHeaders.getFirst("x-goog-api-key")

        val body = """
            {"candidates":[{"content":{"parts":[{"text":"{\"headlines\":[\"테스트 헤드라인\"]}"}]}}]}
        """.trimIndent().toByteArray()

        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
