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
     * 금칙어 포함 시 ChatException을 발생시킴.
     */
    fun validateText(text: String, fieldName: String = "내용") {
        if (containsProfanity(text)) {
            throw ChatException("사용할 수 없는 부적절한 표현이나 사칭 키워드가 포함되어 있습니다 ($fieldName).")
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
