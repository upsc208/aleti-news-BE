package com.atleti.news.news.external

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RepetitionGuardTest {

    @Test
    fun `되풀이가 시작된 자리에서 잘라 낸다`() {
        val text = "시메오네는 팬들에게 응원을 부탁했다.\n\n" + "제발, ".repeat(2000)

        val result = RepetitionGuard.trimLoop(text)

        assertThat(result.looped).isTrue()
        assertThat(result.repeatedUnit).isEqualTo("제발, ")
        assertThat(result.text).isEqualTo("시메오네는 팬들에게 응원을 부탁했다.")
    }

    @Test
    fun `사람이 쓰는 정도의 반복은 그대로 둔다`() {
        val text = "아틀레티 팬들은 절대, 절대, 절대 실패하지 않는다. 다른 방법은 없다. 다른 방법은 없다."

        val result = RepetitionGuard.trimLoop(text)

        assertThat(result.looped).isFalse()
        assertThat(result.text).isEqualTo(text)
    }

    @Test
    fun `문장 단위로 되풀이하는 것도 잡는다`() {
        val sentence = "그것은 큰 영향을 미친다. "
        val result = RepetitionGuard.trimLoop("경기는 수요일에 열린다. " + sentence.repeat(30))

        assertThat(result.looped).isTrue()
        assertThat(result.text).isEqualTo("경기는 수요일에 열린다.")
    }

    @Test
    fun `라벨 숫자만 바뀌며 반복되는 문단을 하나만 남긴다`() {
        val body = "하지만 앙투안 그리즈만은 2022년 10월 이후 득점이 없기 때문에, 앙헬 코레아의 대체자는 멤피스 데파이가 될 것이다."
        val text = "[코레아 부상]\n앙헬 코레아는 훈련 중에 햄스트링 부상을 당했다.\n\n" +
            (1..266).joinToString("\n\n") { "[앙헬 코레아 대체자 $it]\n$body" }

        val result = RepetitionGuard.dropRepeatedParagraphs(text)

        assertThat(result.looped).isTrue()
        assertThat(result.text.lines().count { it.contains("멤피스 데파이") }).isEqualTo(1)
        assertThat(result.text).contains("햄스트링 부상")
    }

    @Test
    fun `내용이 다른 문단은 라벨이 비슷해도 남긴다`() {
        val text = "[오사수나전 리뷰]\n아틀레티코가 4-0으로 이겼다.\n\n" +
            "[오사수나전 리뷰 2]\n조나단 데이비드가 두 골을 넣었다."

        val result = RepetitionGuard.dropRepeatedParagraphs(text)

        assertThat(result.looped).isFalse()
        assertThat(result.text.split("\n\n")).hasSize(2)
    }

    @Test
    fun `멀리 떨어져 두 번 나온 같은 문단은 조용히 하나만 남긴다`() {
        val body = "[부상자 명단]\n바리오스는 결장한다."
        val text = "$body\n\n[리그 순위]\n아틀레티코는 3위다.\n\n$body"

        val result = RepetitionGuard.dropRepeatedParagraphs(text)

        assertThat(result.looped).isFalse()
        assertThat(result.text.split("\n\n")).hasSize(2)
    }

    @Test
    fun `되풀이가 없으면 원문 그대로다`() {
        val text = "[코케 기록]\n코케는 리그 524경기에 나섰다.\n\n[다음 경기]\n내일 상대는 오사수나다."

        assertThat(RepetitionGuard.trimLoop(text).text).isEqualTo(text)
        assertThat(RepetitionGuard.dropRepeatedParagraphs(text).text).isEqualTo(text)
    }
}
