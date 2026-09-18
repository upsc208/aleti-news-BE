package com.atleti.news.admin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class AdminAuthServiceTest {

    private class MemoryAttempts : LoginAttempts {
        val counts = mutableMapOf<String, Long>()
        override fun fail(ip: String): Long = counts.merge(ip, 1L, Long::plus)!!
        override fun failures(ip: String): Long = counts[ip] ?: 0L
        override fun clear(ip: String) { counts.remove(ip) }
    }

    /** Redis가 죽었을 때의 모습 — 세지도 못하고 예외도 삼킨다 */
    private class BrokenAttempts : LoginAttempts {
        override fun fail(ip: String) = 0L
        override fun failures(ip: String) = 0L
        override fun clear(ip: String) {}
    }

    private fun service(
        username: String = "boss",
        password: String = "derby-2026!",
        attempts: LoginAttempts = MemoryAttempts(),
    ) = AdminAuthService(
        MockEnvironment()
            .withProperty("atleti.admin.username", username)
            .withProperty("atleti.admin.password", password),
        attempts,
    )

    @Test
    fun `맞는 아이디와 비밀번호면 토큰을 준다`() {
        val auth = service()

        val token = auth.login("boss", "derby-2026!", "1.2.3.4")

        assertThat(auth.verify(token)).isTrue()
    }

    @Test
    fun `비밀번호가 틀리면 거절한다`() {
        val auth = service()

        assertThatThrownBy { auth.login("boss", "틀린비밀번호", "1.2.3.4") }
            .isInstanceOf(AdminAuthException::class.java)
    }

    @Test
    fun `실패가 쌓이면 비밀번호가 맞아도 막는다`() {
        val attempts = MemoryAttempts()
        val auth = service(attempts = attempts)
        repeat(AdminAuthService.MAX_FAILURES.toInt()) {
            runCatching { auth.login("boss", "틀림", "1.2.3.4") }
        }

        assertThatThrownBy { auth.login("boss", "derby-2026!", "1.2.3.4") }
            .hasMessageContaining("시도가 너무 많습니다")
    }

    @Test
    fun `잠긴 IP가 아니면 그대로 들어간다`() {
        val attempts = MemoryAttempts()
        val auth = service(attempts = attempts)
        repeat(AdminAuthService.MAX_FAILURES.toInt()) {
            runCatching { auth.login("boss", "틀림", "1.2.3.4") }
        }

        assertThat(auth.verify(auth.login("boss", "derby-2026!", "5.6.7.8"))).isTrue()
    }

    @Test
    fun `로그인에 성공하면 실패 기록을 지운다`() {
        val attempts = MemoryAttempts()
        val auth = service(attempts = attempts)
        runCatching { auth.login("boss", "틀림", "1.2.3.4") }

        auth.login("boss", "derby-2026!", "1.2.3.4")

        assertThat(attempts.counts).isEmpty()
    }

    @Test
    fun `실패 횟수를 세지 못해도 로그인은 된다`() {
        val auth = service(attempts = BrokenAttempts())

        assertThat(auth.verify(auth.login("boss", "derby-2026!", "1.2.3.4"))).isTrue()
    }

    @Test
    fun `서명이 손상된 토큰은 통과하지 못한다`() {
        val auth = service()
        val token = auth.login("boss", "derby-2026!", "1.2.3.4")

        val payload = token.substringBefore(".")
        assertThat(auth.verify("$payload.가짜서명")).isFalse()
        assertThat(auth.verify("아무거나")).isFalse()
        assertThat(auth.verify(null)).isFalse()
    }

    @Test
    fun `다른 인스턴스가 만든 토큰은 통과하지 못한다`() {
        val token = service().login("boss", "derby-2026!", "1.2.3.4")

        assertThat(service().verify(token)).isFalse()
    }

    @Test
    fun `계정이 설정되지 않으면 로그인 기능이 잠긴다`() {
        val auth = service(username = "", password = "")

        assertThat(auth.enabled).isFalse()
        assertThatThrownBy { auth.login("boss", "derby-2026!", "1.2.3.4") }
            .hasMessageContaining("설정되어 있지 않습니다")
    }

    @Test
    fun `짧은 비밀번호는 계정으로 인정하지 않는다`() {
        assertThat(service(password = "1234").enabled).isFalse()
    }
}
