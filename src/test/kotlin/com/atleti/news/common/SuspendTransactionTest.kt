package com.atleti.news.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `@Transactional`이 코루틴에서 어디까지 걸리는지 **실제로 재 본다.**
 *
 * 스프링의 JPA 트랜잭션은 `ThreadLocal`에 묶인다. 그래서 `withContext(Dispatchers.IO)`처럼
 * 스레드를 옮기는 순간 그 안쪽은 **트랜잭션 밖**이 된다. 애노테이션은 붙어 있고 컴파일도 되고
 * 에러도 안 나는데 경계만 사라지는, 눈으로는 알 수 없는 종류의 문제다.
 *
 * 실제로 `MatchScraperService.scrapeAndSaveLatestMatch`가 이 모양이었다
 * (`@Transactional suspend fun ... = withContext(Dispatchers.IO) { ... delete ... save ... }`).
 * 이 테스트는 그 판단의 근거를 코드로 남겨, 나중에 누군가 같은 조합을 다시 쓰지 않게 한다.
 *
 * 결론: 코루틴 안에서 원자성이 필요하면 `TransactionTemplate`으로 **명시적 경계**를 친다.
 */
@ActiveProfiles("h2")
@SpringBootTest
class SuspendTransactionTest {

    @Component
    class TxProbe(private val transactionTemplate: TransactionTemplate) {

        /** 평범한 블로킹 메서드 — 프록시가 트랜잭션을 연다 */
        @Transactional
        fun blocking(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

        /** suspend + withContext — 스레드가 바뀌어 안쪽은 트랜잭션 밖이다 */
        @Transactional
        suspend fun suspendWithContext(): Boolean = withContext(Dispatchers.IO) {
            TransactionSynchronizationManager.isActualTransactionActive()
        }

        /** 고친 방식 — withContext 안에서 TransactionTemplate으로 직접 경계를 친다 */
        suspend fun suspendWithTemplate(): Boolean = withContext(Dispatchers.IO) {
            transactionTemplate.execute {
                TransactionSynchronizationManager.isActualTransactionActive()
            } ?: false
        }
    }

    @TestConfiguration
    class Probes {
        @Bean
        fun txProbe(transactionTemplate: TransactionTemplate) = TxProbe(transactionTemplate)
    }

    @Autowired
    private lateinit var probe: TxProbe

    @Test
    @DisplayName("블로킹 메서드에는 트랜잭션이 걸린다 — 대조군")
    fun testBlockingHasTransaction() {
        assertTrue(
            probe.blocking(),
            "이게 false면 프록시 자체가 안 걸린 것이므로 아래 결과도 의미가 없다"
        )
    }

    @Test
    @DisplayName("suspend + withContext 안쪽은 트랜잭션 밖이다")
    fun testSuspendWithContextHasNoTransaction() = runBlocking {
        assertFalse(
            probe.suspendWithContext(),
            "@Transactional suspend fun = withContext { ... } 는 경계가 없다. " +
                "이 안에서 delete + save를 하면 중간 실패 시 반쪽만 반영된다"
        )
    }

    @Test
    @DisplayName("TransactionTemplate으로 치면 코루틴 안에서도 경계가 생긴다 — 고친 방식")
    fun testSuspendWithTemplateHasTransaction() = runBlocking {
        assertTrue(
            probe.suspendWithTemplate(),
            "MatchScraperService·HeadlineService가 이 방식으로 원자성을 얻는다"
        )
    }
}
