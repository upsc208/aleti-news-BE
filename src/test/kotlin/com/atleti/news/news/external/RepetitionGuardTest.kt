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
    fun `되풀이가 없으면 원문 그대로다`() {
        val text = "코케는 리그 524경기에 나섰다.\n\n내일 상대는 오사수나다."

        assertThat(RepetitionGuard.trimLoop(text).text).isEqualTo(text)
    }

    @Test
    fun `문장 단위로 되풀이하는 것도 잡는다`() {
        val sentence = "그것은 큰 영향을 미친다. "
        val result = RepetitionGuard.trimLoop("경기는 수요일에 열린다. " + sentence.repeat(30))

        assertThat(result.looped).isTrue()
        assertThat(result.text).isEqualTo("경기는 수요일에 열린다.")
    }
}
