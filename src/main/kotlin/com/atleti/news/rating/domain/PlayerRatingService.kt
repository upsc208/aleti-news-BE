package com.atleti.news.rating.domain

import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.LineupInfo
import com.atleti.news.match.domain.PlayerInfo
import com.atleti.news.match.domain.SubstitutionInfo
import com.atleti.news.match.domain.MatchResultRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class PlayerRatingService(
    private val matchResultRepository: MatchResultRepository,
    private val playerRatingVoteRepository: PlayerRatingVoteRepository,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val MIN_SCORE = 0
        const val MAX_SCORE = 5
    }

    // 해당 경기에서 뛴 아틀레티코 선수 목록 (선발 + 교체 투입). isHome으로 아틀레티코 진영 판별.
    private fun atletiPlayers(match: MatchResult): List<PlayerInfo> {
        val lineup = match.lineup
            ?.let { try { objectMapper.readValue<LineupInfo>(it) } catch (e: Exception) { null } }
            ?: return emptyList()
        val subs = match.substitutions
            ?.let { try { objectMapper.readValue<List<SubstitutionInfo>>(it) } catch (e: Exception) { null } }
            ?: emptyList()

        val team = if (match.isHome) lineup.home else lineup.away
        val subbedIn = subs.filter { it.isHome == match.isHome }.map { PlayerInfo(name = it.inName, shirt = null) }

        // 이름 기준 중복 제거 (선발 정보 우선)
        val ordered = LinkedHashMap<String, PlayerInfo>()
        (team.starters + subbedIn).forEach { p -> if (p.name.isNotBlank()) ordered.putIfAbsent(p.name, p) }
        return ordered.values.toList()
    }

    fun getRatings(matchId: String, ip: String): List<PlayerRating> {
        val match = matchResultRepository.findByMatchId(matchId) ?: return emptyList()
        val players = atletiPlayers(match)
        val aggMap = playerRatingVoteRepository.aggregateByMatch(matchId).associateBy { it.playerName }
        val myVotes = playerRatingVoteRepository.findByMatchIdAndIpAddress(matchId, ip).associate { it.playerName to it.score }

        return players.map { p ->
            val agg = aggMap[p.name]
            PlayerRating(
                playerName = p.name,
                shirt = p.shirt,
                averageScore = agg?.average() ?: 0.0,
                voteCount = agg?.voteCount ?: 0L,
                myScore = myVotes[p.name]
            )
        }
    }

    @Transactional
    fun submitRating(matchId: String, playerName: String, score: Int, ip: String): PlayerRating {
        require(score in MIN_SCORE..MAX_SCORE) { "점수는 $MIN_SCORE~$MAX_SCORE 사이여야 합니다. (입력: $score)" }

        val match = matchResultRepository.findByMatchId(matchId)
            ?: throw NoSuchElementException("해당 경기를 찾을 수 없습니다: $matchId")

        // 실제로 그 경기에서 뛴 아틀레티코 선수만 평가 가능 (임의 이름 스팸 방지)
        val player = atletiPlayers(match).firstOrNull { it.name == playerName }
            ?: throw IllegalArgumentException("이 경기에서 뛴 아틀레티코 선수가 아닙니다: $playerName")

        val existing = playerRatingVoteRepository.findByMatchIdAndPlayerNameAndIpAddress(matchId, playerName, ip)
        if (existing != null) {
            // 재투표: 점수 수정
            existing.score = score
            existing.updatedAt = OffsetDateTime.now()
            playerRatingVoteRepository.save(existing)
        } else {
            playerRatingVoteRepository.save(
                PlayerRatingVote(
                    matchId = matchId,
                    playerName = playerName,
                    shirt = player.shirt,
                    ipAddress = ip,
                    score = score
                )
            )
        }

        // 저장 직후 평균 재계산해서 반환 (즉시 반영)
        val agg = playerRatingVoteRepository.aggregateByMatch(matchId).firstOrNull { it.playerName == playerName }
        return PlayerRating(
            playerName = playerName,
            shirt = player.shirt,
            averageScore = agg?.average() ?: score.toDouble(),
            voteCount = agg?.voteCount ?: 1L,
            myScore = score
        )
    }
}
