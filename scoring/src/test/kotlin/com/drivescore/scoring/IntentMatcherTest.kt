package com.drivescore.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntentMatcherTest {
    private fun i(s: String) = IntentMatcher.match(s)?.intent

    @Test fun `SPEC 4 발화 예시`() {
        mapOf(
            "네" to Intent.YES, "응" to Intent.YES, "좋아" to Intent.YES, "네 좋아요" to Intent.YES,
            "아니오" to Intent.NO, "아니" to Intent.NO, "괜찮아" to Intent.NO,
            "오늘 미션 뭐야" to Intent.MISSION,
            "오늘은 아내가 운전해" to Intent.SWITCH_DRIVER, "내가 운전해" to Intent.SWITCH_DRIVER,
            "이번엔 기록하지 마" to Intent.SKIP_RECORD,
            "리플레이 보여줘" to Intent.REPLAY, "다시 보여줘" to Intent.REPLAY,
            "다음" to Intent.NEXT, "건너뛰기" to Intent.NEXT,
            "왜 감점됐어" to Intent.WHY, "왜 감점됐어?" to Intent.WHY,
            "지난주랑 비교해줘" to Intent.COMPARE,
            "내 등급 알려줘" to Intent.GRADE,
        ).forEach { (text, want) -> assertEquals(want, i(text), text) }
    }

    @Test fun `네로 시작하는 다른 말은 YES가 아니다`() {
        assertNull(i("네비 켜줘"))
        assertNull(i("날씨 어때"))
        assertNull(i(""))
    }

    @Test fun `운전자 이름을 뽑는다`() {
        assertEquals("아내", IntentMatcher.match("오늘은 아내가 운전해")?.driver)
        assertEquals("나", IntentMatcher.match("내가 운전해")?.driver)
    }

    @Test fun `주행 중에는 허용된 의도만 받는다`() {
        val driving = setOf(Intent.YES, Intent.NO)
        assertNull(IntentMatcher.match("리플레이 보여줘", driving))
        assertEquals(Intent.YES, IntentMatcher.match("네", driving)?.intent)
    }
}
