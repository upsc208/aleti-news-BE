package com.atleti.news.news.external

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OffTopicFilterTest {

    @Test
    fun `비축구 표시가 붙은 문단을 걷어낸다`() {
        val text = "${OffTopicFilter.FOOTBALL_LABEL}[오사수나전 리뷰]\n아틀레티코가 4-0으로 이겼다.\n\n" +
            "${OffTopicFilter.OFF_TOPIC_LABEL}[영화 홍보]\n10월 30일에 개봉하는 애니메이션을 소개했다.\n\n" +
            "${OffTopicFilter.OFF_TOPIC_LABEL}[F1 경기 관람]\n출연진은 최근 F1 경기가 지루했다고 말했다."

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo("[오사수나전 리뷰]\n아틀레티코가 4-0으로 이겼다.")
        assertThat(result.dropped).hasSize(2)
    }

    @Test
    fun `판매 문구가 있으면 축구 이야기여도 걷어낸다`() {
        val text = "[코케의 기록]\n코케는 745경기에 출전했다.\n\n" +
            "[사인볼 이벤트]\n웹사이트에서 제품을 구매하고 할인 코드를 쓰면 선수 사인볼 추첨에 참여할 수 있다."

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo("[코케의 기록]\n코케는 745경기에 출전했다.")
        assertThat(result.dropped).hasSize(1)
    }

    @Test
    fun `표시가 없으면 그대로 둔다`() {
        val text = "[레알 마드리드전 예상 라인업]\n오블락, 요렌테, 르노르망, 쿠티 로메로, 갈란, 코케.\n\n" +
            "[회장 선거]\n세레소 회장의 연임에 반대하는 목소리가 나왔다.\n\n" +
            "[일정 부담]\n선수들은 F1 경기를 보러 갈 시간도 없이 다음 경기를 준비한다."

        val result = OffTopicFilter.clean(text)

        assertThat(result.text).isEqualTo(text)
        assertThat(result.dropped).isEmpty()
    }
}
