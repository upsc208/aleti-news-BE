package com.atleti.news.rating.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PlayerRatingVoteRepository : JpaRepository<PlayerRatingVote, Long> {

    // 재투표(upsert) 판별용
    fun findByMatchIdAndPlayerNameAndIpAddress(matchId: String, playerName: String, ipAddress: String): PlayerRatingVote?

    // 이 IP가 이번 경기에서 이미 준 점수들 (프론트에 내 점수 표시용)
    fun findByMatchIdAndIpAddress(matchId: String, ipAddress: String): List<PlayerRatingVote>

    // 선수별 합계/투표수 실시간 집계 (평균은 서비스에서 계산 — DB별 AVG 반환 타입 이슈 회피)
    @Query("""
        SELECT new com.atleti.news.rating.domain.PlayerRatingAggregate(v.playerName, SUM(v.score), COUNT(v))
        FROM PlayerRatingVote v
        WHERE v.matchId = :matchId
        GROUP BY v.playerName
    """)
    fun aggregateByMatch(@Param("matchId") matchId: String): List<PlayerRatingAggregate>
}
