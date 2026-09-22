package com.atleti.news.admin

import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

interface LoginAttempts {
    fun fail(ip: String): Long
    fun failures(ip: String): Long
    fun clear(ip: String)
}

class AdminAuthException(message: String) : RuntimeException(message)

/* 관리자만 쓰는 로그인. 관리자 경로에만 붙는 토큰을 발급하고 검사*/
@Service
class AdminAuthService(
    private val environment: Environment,
    private val attempts: LoginAttempts,
) {
    private val username: String = property("atleti.admin.username")
    private val password: String = property("atleti.admin.password")

    private val signingKey: ByteArray = property("atleti.admin.token-secret")
        .takeIf { it.isNotBlank() }
        ?.toByteArray(StandardCharsets.UTF_8)
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    val enabled: Boolean = username.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH

    // 비밀번호를 검증 전 실패 횟수부터 막음
    fun login(inputUsername: String, inputPassword: String, ip: String): String {
        if (!enabled) throw AdminAuthException("관리자 계정이 설정되어 있지 않습니다.")

        if (attempts.failures(ip) >= MAX_FAILURES) {
            throw AdminAuthException("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.")
        }

        val ok = constantTimeEquals(inputUsername, username) && constantTimeEquals(inputPassword, password)
        if (!ok) {
            attempts.fail(ip)
            throw AdminAuthException("아이디 또는 비밀번호가 올바르지 않습니다.")
        }

        attempts.clear(ip)
        return issueToken()
    }

    // 서명과 만료 시각만 보면 됨. 발급한 토큰을 따로 저장 X
    fun verify(token: String?): Boolean {
        if (!enabled || token.isNullOrBlank()) return false
        val parts = token.split(".")
        if (parts.size != 2) return false

        val payload = parts[0]
        if (!constantTimeEquals(parts[1], sign(payload))) return false

        val expiresAt = decode(payload).substringBefore(":").toLongOrNull() ?: return false
        return Instant.now().epochSecond < expiresAt
    }

    private fun issueToken(): String {
        val expiresAt = Instant.now().epochSecond + TOKEN_TTL_SECONDS
        val payload = encode("$expiresAt:${Random.nextLong()}")
        return "$payload.${sign(payload)}"
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun encode(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

    private fun decode(encoded: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8) }.getOrDefault("")

    private fun property(key: String): String = environment.getProperty(key).orEmpty().trim()

    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(StandardCharsets.UTF_8),
        b.toByteArray(StandardCharsets.UTF_8),
    )

    companion object {
        const val TOKEN_TTL_SECONDS = 60L * 60 * 12
        const val MAX_FAILURES = 10L
        const val MIN_PASSWORD_LENGTH = 8
    }
}
