package com.atleti.news.chat.domain

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertFailsWith
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

    @Test
    @DisplayName("전화번호가 형태를 바꿔도 감지된다")
    fun testPhoneNumberDetection() {
        assertTrue(slangFilterService.containsContactInfo("010-1234-5678"))
        assertTrue(slangFilterService.containsContactInfo("01012345678"))
        assertTrue(slangFilterService.containsContactInfo("연락처 010 1234 5678 로 주세요"))
        assertTrue(slangFilterService.containsContactInfo("010.1234.5678"))
        assertTrue(slangFilterService.containsContactInfo("02-1234-5678"))
        // 잘게 쪼개는 우회 — 공백·특수문자를 지우면 휴대폰 번호 모양
        assertTrue(slangFilterService.containsContactInfo("0 1 0 . 1 2 3 4 . 5 6 7 8"))
    }

    @Test
    @DisplayName("계좌번호가 감지된다")
    fun testAccountNumberDetection() {
        assertTrue(slangFilterService.containsContactInfo("3333-01-1234567 입금해주세요"))  // 카카오뱅크
        assertTrue(slangFilterService.containsContactInfo("110-123-456789"))               // 신한
        assertTrue(slangFilterService.containsContactInfo("1002 123 456789"))              // 우리
        assertTrue(slangFilterService.containsContactInfo("국민 12345678901234"))
    }

    @Test
    @DisplayName("축구 채팅에 흔한 숫자(스코어·날짜·시즌·등번호)는 연락처로 오탐하지 않는다")
    fun testNoFalsePositiveOnCommonNumbers() {
        assertFalse(slangFilterService.containsContactInfo("오늘 3-1로 이겼다"))
        assertFalse(slangFilterService.containsContactInfo("4-4-2 포메이션이 낫지 않나"))
        assertFalse(slangFilterService.containsContactInfo("다음 경기 2026-08-15 21:00 킥오프"))
        assertFalse(slangFilterService.containsContactInfo("2025-2026 시즌 기대된다"))
        assertFalse(slangFilterService.containsContactInfo("등번호 7, 9, 10, 11, 14, 20, 21"))
        assertFalse(slangFilterService.containsContactInfo("전반 45분 후반 90분 총 135분"))
    }

    @Test
    @DisplayName("validateText는 금칙어와 연락처를 서로 다른 안내 문구로 막는다")
    fun testValidateTextMessages() {
        val profanity = assertFailsWith<ChatException> {
            slangFilterService.validateText("씨발", "메시지")
        }
        assertTrue(profanity.message!!.contains("부적절한 표현"))

        val contact = assertFailsWith<ChatException> {
            slangFilterService.validateText("010-1234-5678", "메시지")
        }
        assertTrue(contact.message!!.contains("전화번호"))

        // 정상 메시지는 통과
        slangFilterService.validateText("오늘 경기 3-1 승리!", "메시지")
    }
}
