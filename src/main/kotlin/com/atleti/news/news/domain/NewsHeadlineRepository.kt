package com.atleti.news.news.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NewsHeadlineRepository : JpaRepository<NewsHeadline, Long> {
    // 커서(직전 생성 시각) 계산용: 가장 최근 헤드라인
    fun findTopByOrderByCreatedAtDesc(): NewsHeadline?

    // 최신순 전체 조회 (중복 방지 참고 + 롤링 5개 유지용)
    fun findAllByOrderByCreatedAtDesc(): List<NewsHeadline>

    // 프론트 노출용: 최신 5개
    fun findTop5ByOrderByCreatedAtDesc(): List<NewsHeadline>
}
