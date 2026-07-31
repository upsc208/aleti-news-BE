package com.atleti.news.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlangFilterServiceTest {

    private lateinit var slangFilterService: SlangFilterService

    @BeforeEach
    fun setUp() {
        val resolver = PathMatchingResourcePatternResolver(DefaultResourceLoader())
        slangFilterService = SlangFilterService(resolver)
        slangFilterService.init()
    }

    @Test
    @DisplayName("일반 정상 메시지는 금칙어 검사를 통과한다")
    fun testCleanText() {
        assertFalse(slangFilterService.containsProfanity("안녕하세요 아틀레티코 마드리드 팬입니다!"))
        assertFalse(slangFilterService.containsProfanity("오늘 훌리안 알바레스 골 넣었나요?"))
    }

    @Test
    @DisplayName("기본 금칙어 및 LOL 비속어가 감지된다")
    fun testProfanityDetection() {
        assertTrue(slangFilterService.containsProfanity("씨발"))
        assertTrue(slangFilterService.containsProfanity("18새끼"))
    }

    @Test
    @DisplayName("공백 및 특수문자가 섞인 변형 금칙어가 정규화를 통해 감지된다")
    fun testNormalizedProfanity() {
        assertTrue(slangFilterService.containsProfanity("씨.발"))
        assertTrue(slangFilterService.containsProfanity("18.새.끼"))
    }

    @Test
    @DisplayName("사칭 방지 키워드(admin, 관리자, 운영자 등)가 감지된다")
    fun testAdminKeywordDetection() {
        assertTrue(slangFilterService.containsProfanity("admin"))
        assertTrue(slangFilterService.containsProfanity("관리자"))
        assertTrue(slangFilterService.containsProfanity("운영자"))
        assertTrue(slangFilterService.containsProfanity("알레티관리자"))
    }

    @Test
    @DisplayName("배민 이중검증 모델: 화이트리스트 단어(고르곤졸라)는 금칙어(졸라)가 포함되어도 통과한다")
    fun testWhitelistExemption() {
        // '고르곤졸라'에는 '졸라'가 포함되어 있으나 화이트리스트에 의해 통과해야 함
        assertFalse(slangFilterService.containsProfanity("고르곤졸라 피자 맛있네요"))

        // 반면 '고르곤'과 '졸라'가 띄어쓰기되면 화이트리스트에 매칭되지 않아 차단됨
        assertTrue(slangFilterService.containsProfanity("고르곤 졸라 심하네"))
    }

    @Test
    @DisplayName("화이트리스트는 금칙어 자체를 덮어쓰지 않는다 — 블랙리스트와 중복된 단어는 차단 유지")
    fun testWhitelistDoesNotShadowBlacklist() {
        // 화이트리스트에 블랙리스트와 같은 단어가 들어가면 그 단어가 통째로 풀린다.
        // '개새'가 양쪽에 있어 무력화됐던 사례의 회귀 방지.
        assertTrue(slangFilterService.containsProfanity("개새"))
        assertTrue(slangFilterService.containsProfanity("이 개새 뭐야"))
        assertTrue(slangFilterService.containsProfanity("개새끼"))
    }
}
