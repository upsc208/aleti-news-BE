package com.atleti.news.news.external

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OffTopicFilterTest {

    @Test
    fun `협찬 문단을 걷어낸다`() {
        val text = """
            [코케의 기록]
            코케는 745경기에 출전했다.

            [Casa Santona 이벤트]
            웹사이트에서 제품을 구매하고 할인 코드를 사용하면 추첨에 참여할 수 있다.
        """.trimIndent()

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo("[코케의 기록]\n코케는 745경기에 출전했다.")
        assertThat(result.dropped).hasSize(1)
    }

    @Test
    fun `축구가 아닌 종목 문단을 걷어낸다`() {
        val text = "[F1 경기 관람]\n출연진은 최근 F1 경기가 지루했다고 말했다.\n\n[다음 경기]\n수요일에 오사수나와 경기를 한다."

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo("[다음 경기]\n수요일에 오사수나와 경기를 한다.")
    }

    @Test
    fun `영화 홍보 문단을 걷어낸다`() {
        val text = "[오사수나전 리뷰]\n아틀레티코가 4-0으로 이겼다.\n\n" +
            "[영화 홍보]\n10월 30일에 개봉하는 애니메이션을 소개했다. 진행자가 한 배역의 목소리를 맡았다고 밝혔다."

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo("[오사수나전 리뷰]\n아틀레티코가 4-0으로 이겼다.")
        assertThat(result.dropped).hasSize(1)
    }

    @Test
    fun `선수 이름만 나열된 라인업 문단은 살린다`() {
        val text = "[레알 마드리드전 예상 라인업]\n오블락, 요렌테, 르노르망, 쿠티 로메로, 갈란, 코케, 카르도소, 캉인, 바에나, 그리즈만, 조나단 데이비드."

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo(text)
        assertThat(result.dropped).isEmpty()
    }

    @Test
    fun `축구 얘기 중에 스친 다른 종목은 문단을 살린다`() {
        val text = "[일정 부담]\n선수들은 F1 경기를 보러 갈 시간도 없이 다음 경기를 준비한다."

        assertThat(OffTopicFilter.clean(text).text).isEqualTo(text)
        assertThat(OffTopicFilter.clean(text).dropped).isEmpty()
    }

    @Test
    fun `평범한 번역은 그대로 둔다`() {
        val text = "[부상자 명단]\n바리오스는 결장한다.\n\n[코케 활약]\n코케가 경기 흐름을 바꿨다."

        assertThat(OffTopicFilter.clean(text).text).isEqualTo(text)
    }
}
