package com.atleti.news.repository

/**
 * 선수별 평점 집계 결과 — [PlayerRatingVoteRepository.aggregateByMatch]의 JPQL 생성자 표현식이
 * 직접 인스턴스화한다 (docs/architecture.md 1.2-②).
 *
 * **리포지토리 계층에 둔다.** JPQL의 `new` 절은 FQCN을 문자열로 적기 때문에 컴파일러가
 * 검증해 주지 않는다. 이 클래스가 상위 계층(`service`)에 있으면 리포지토리가 서비스를
 * 참조하게 되고, 그 결합은 컴파일 타임에 안 잡혀 **IDE 리팩토링으로 옮기는 순간 조용히 깨진다.**
 * 쿼리 결과 투영(projection)이므로 쿼리와 같은 계층에 있는 것이 맞다.
 *
 * 평균을 DB에서 구하지 않고 합계·건수만 받아 오는 이유는 `AVG()`의 반환 타입이 DB마다 달라서다
 * (운영은 PostgreSQL, 테스트는 H2). 나눗셈은 여기서 한다.
 */
data class PlayerRatingAggregate(
    val playerName: String,
    val sumScore: Long,
    val voteCount: Long
) {
    /** 소수 첫째 자리까지. 투표가 없으면 0.0 */
    fun average(): Double =
        if (voteCount > 0) Math.round(sumScore.toDouble() / voteCount * 10.0) / 10.0 else 0.0
}
