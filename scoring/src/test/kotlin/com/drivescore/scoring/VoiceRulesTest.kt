package com.drivescore.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceRulesTest {
    @Test fun `주행 중에는 한 문장만 허용한다`() {
        assertTrue(DrivingSpeech.allowed("지난번 감점 구간이에요."))
        assertFalse(DrivingSpeech.allowed("감점 구간이에요. 조심하세요."))
    }

    @Test fun `주행 중에는 숫자를 말하지 않는다`() = assertFalse(DrivingSpeech.allowed("오늘 85점이에요."))

    @Test fun `빈 문장은 말하지 않는다`() = assertFalse(DrivingSpeech.allowed("  "))

    @Test fun `지점 300m 전에 한 번만 알린다`() {
        val a = HazardAlerter(listOf(1000.0), emptyList())
        assertFalse(a.poll(699.0, 50.0))
        assertTrue(a.poll(700.0, 50.0))
        assertFalse(a.poll(710.0, 50.0))
    }

    @Test fun `시작부터 예고 거리 안이면 출발할 때 알린다`() {
        val a = HazardAlerter(listOf(150.0), emptyList())
        assertFalse(a.poll(0.0, 0.0))
        assertTrue(a.poll(1.0, 20.0))
    }

    @Test fun `회전 10초 전에는 보류하고 지나면 알린다`() {
        val a = HazardAlerter(listOf(1000.0), listOf(800.0))
        assertFalse(a.poll(700.0, 36.0)) // 회전까지 100m, 10초
        assertTrue(a.poll(801.0, 36.0))
    }

    @Test fun `보류된 예고는 지점을 지나면 버린다`() {
        val a = HazardAlerter(listOf(1000.0), listOf(1100.0))
        assertFalse(a.poll(1000.0, 72.0))
        assertFalse(a.poll(1200.0, 72.0))
    }

    @Test fun `가까이 몰린 감점은 한 번만 알린다`() {
        val a = HazardAlerter(listOf(1000.0, 1200.0, 1700.0), emptyList())
        val fired = listOf(700.0, 1100.0, 1250.0, 1400.0).count { a.poll(it, 50.0) }
        assertEquals(2, fired)
    }
}
