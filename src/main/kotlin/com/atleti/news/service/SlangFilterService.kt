package com.atleti.news.service

import jakarta.annotation.PostConstruct
import org.ahocorasick.trie.Emit
import org.ahocorasick.trie.Trie
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 아호코라식(Aho-Corasick) 알고리즘 + 배달의민족 이중 검증(화이트리스트/블랙리스트) 기반 금칙어 필터 서비스.
 * - O(N) 문장 길이에 비례하는 속도 (0.1ms 미만)
 * - 공백/특수문자 정규화 (씨.발 -> 씨발 변형 방지)
 * - 고르곤졸라 내 '졸라' 오탐 방지 (화이트리스트 범위 비교)
 */
@Service
class SlangFilterService(
    private val resourceResolver: ResourcePatternResolver
) {
    private val log = LoggerFactory.getLogger(SlangFilterService::class.java)

    private lateinit var blacklistTrie: Trie
    private lateinit var whitelistTrie: Trie

    companion object {
        /** 숫자 묶음이 구분자(공백/하이픈/점/괄호/ㅡ 등)로 최대 3개까지 이어진 형태 */
        private val CONTACT_GROUPS = Regex("""\d+(?:[\s.\-_/~·,()ㅡ]{1,2}\d+){0,2}""")
        private val DIGIT_RUN = Regex("""\d+""")
        /** 휴대폰: 010/011/016/017/018/019 + 7~8자리 */
        private val MOBILE_NUMBER = Regex("""01[016789]\d{7,8}""")
        /** 계좌번호(10~14자리)와 전화번호(10~11자리)를 함께 덮는 범위 */
        private const val CONTACT_MIN_DIGITS = 10
        private const val CONTACT_MAX_DIGITS = 16
    }

    @PostConstruct
    fun init() {
        reload()
    }

    /**
     * slang/ 디렉터리의 텍스트 파일들을 읽어 아호코라식 트라이 구축.
     * 관리자 API나 기동 시 호출 가능.
     */
    @Synchronized
    fun reload() {
        val blacklistWords = mutableSetOf<String>()
        val whitelistWords = mutableSetOf<String>()

        // 1. 블랙리스트 파일 로딩 (LOL 비속어 리스트 + custom_blacklist.txt)
        val lolResources = runCatching { resourceResolver.getResources("classpath:slang/*필터링*.txt") }.getOrDefault(emptyArray())
        val customResources = runCatching { resourceResolver.getResources("classpath:slang/custom_blacklist.txt") }.getOrDefault(emptyArray())
        val blacklistResources: Array<Resource> = lolResources + customResources

        for (res in blacklistResources) {
            if (!res.exists()) continue
            res.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("@!@") }
                        .forEach { blacklistWords.add(it) }
                }
            }
        }

        // 2. 화이트리스트 파일 로딩 (whitelist.txt)
        val whitelistResources: Array<Resource> = runCatching { resourceResolver.getResources("classpath:slang/whitelist.txt") }.getOrDefault(emptyArray())
        for (res in whitelistResources) {
            if (!res.exists()) continue
            res.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { whitelistWords.add(it) }
                }
            }
        }

        // 3. Trie 빌드
        val blBuilder = Trie.builder().ignoreCase().ignoreOverlaps()
        blacklistWords.forEach { blBuilder.addKeyword(it) }
        blacklistTrie = blBuilder.build()

        val wlBuilder = Trie.builder().ignoreCase().ignoreOverlaps()
        whitelistWords.forEach { wlBuilder.addKeyword(it) }
        whitelistTrie = wlBuilder.build()

        log.info("✅ 금칙어 필터링 시스템 초기화 완료 — 블랙리스트: {}개, 화이트리스트: {}개 단어", blacklistWords.size, whitelistWords.size)
    }

    /**
     * 텍스트에 금칙어가 포함되어 있는지 검사.
     * @return true면 금칙어가 포함되어 차단해야 함.
     */
    fun containsProfanity(text: String): Boolean {
        if (text.isBlank()) return false

        // 1. 원본 텍스트 검사
        if (checkText(text)) return true

        // 2. 정규화 텍스트 검사 (공백, 특수문자 제거 후 검사)
        val normalized = normalize(text)
        if (normalized != text && checkText(normalized)) return true

        return false
    }

    /**
     * 전화번호·계좌번호로 보이는 숫자가 있는지 검사 (광고·사기 유도 차단).
     *
     * 단어 목록으로는 잡을 수 없어 숫자의 "모양"으로 판정한다. 축구 채팅에는
     * 스코어(3-1), 날짜(2026-08-15), 시즌(2025-2026), 등번호 나열(7, 9, 10, 11)처럼
     * 숫자가 흔하므로, 오탐을 피하려고 두 가지 조건을 각각 좁게 잡았다.
     *
     * @return true면 연락처로 보여 차단해야 함.
     */
    fun containsContactInfo(text: String): Boolean {
        // 1) 구분자를 낀 원본 형태 — 숫자 묶음 3개 이내이면서 숫자 합이 10~16자리
        //    잡힘: 010-1234-5678 / 3333-01-1234567 / 110 123 456789 / 01012345678
        //    통과: 2026-08-15(8자리) / 2025-2026(8자리) / 4-4-2(3자리)
        //          "2026-08-15 21:00"은 묶음 3개 제한에 걸려 날짜까지만 한 덩어리로 본다
        for (match in CONTACT_GROUPS.findAll(text)) {
            val digitCount = match.value.count { it.isDigit() }
            if (digitCount in CONTACT_MIN_DIGITS..CONTACT_MAX_DIGITS) return true
        }

        // 2) 공백·특수문자를 모두 지운 뒤, 숫자 덩어리 "전체"가 휴대폰 번호 모양인지
        //    "0 1 0 . 1 2 3 4 . 5 6 7 8"처럼 잘게 쪼갠 우회를 잡는다.
        //    덩어리 일부가 아니라 전체가 일치해야 하므로, 등번호를 이어붙인
        //    "791011142021" 같은 숫자에는 걸리지 않는다.
        for (run in DIGIT_RUN.findAll(normalize(text))) {
            if (MOBILE_NUMBER.matches(run.value)) return true
        }

        return false
    }

    /**
     * 금칙어·연락처 포함 시 ChatException을 발생시킴.
     */
    fun validateText(text: String, fieldName: String = "내용") {
        if (containsProfanity(text)) {
            throw ChatException("사용할 수 없는 부적절한 표현이나 사칭 키워드가 포함되어 있습니다 ($fieldName).")
        }
        if (containsContactInfo(text)) {
            throw ChatException("전화번호나 계좌번호로 보이는 숫자는 입력할 수 없습니다 ($fieldName).")
        }
    }

    private fun checkText(input: String): Boolean {
        val blackEmits = blacklistTrie.parseText(input)
        if (blackEmits.isEmpty()) return false

        val whiteEmits = whitelistTrie.parseText(input)
        if (whiteEmits.isEmpty()) return true // 화이트리스트 매칭이 없으면 바로 차단

        // 화이트리스트가 포함하는 범위 내의 금칙어는 제외
        for (black in blackEmits) {
            val isWhitelisted = whiteEmits.any { white ->
                black.start >= white.start && black.end <= white.end
            }
            if (!isWhitelisted) {
                return true // 화이트리스트에 감싸지지 않은 독립 금칙어 존재
            }
        }

        return false
    }

    /**
     * 특수문자, 문장부호, 공백을 제거하고 소문자화
     */
    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-zA-Z0-9가-힣ㄱ-ㅎㅏ-ㅣ]"), "")
    }
}
