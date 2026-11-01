package com.drivescore.scoring

import kotlin.test.*

class ReplayTimelineTest {
    private fun p(t: Double, severity: Double = 1.0) = Penalty(Category.STRAIGHT, PenaltyReason.STEERING_WOBBLE, t, 0.0, severity)

    @Test fun `감점 전후 5초만 1배속이고 시간 축이 빠짐없이 이어진다`() {
        val tl = ReplayTimeline.build(92.0, listOf(p(15.0), p(30.0), p(88.0)))
        assertEquals(listOf(10.0..20.0, 25.0..35.0, 83.0..92.0), tl.segments.filter { it.speed == 1.0 }.map { it.startT..it.endT })
        tl.segments.zipWithNext { a, b -> assertEquals(a.endT, b.startT) }
        assertEquals(92.0, tl.durationSec)
        assertTrue(tl.segments.filter { it.speed != 1.0 }.all { it.speed == 8.0 })
    }

    @Test fun `겹치는 창은 합친다`() {
        val tl = ReplayTimeline.build(100.0, listOf(p(50.0), p(53.0)))
        assertEquals(listOf(45.0..58.0), tl.segments.filter { it.speed == 1.0 }.map { it.startT..it.endT })
    }

    @Test fun `시연 길이는 30초에서 60초 사이다`() {
        val play = ReplayTimeline.build(92.0, listOf(p(15.0), p(30.0), p(88.0))).playDurationSec
        assertTrue(play in 30.0..60.0, "play=$play")
    }

    @Test fun `감점 없는 긴 주행도 60초를 넘지 않는다`() {
        assertEquals(60.0, ReplayTimeline.build(7200.0, emptyList()).playDurationSec, 0.001)
    }

    @Test fun `감점이 넘치면 심각도 높은 것만 1배속으로 남긴다`() {
        val many = (1..20).map { p(it * 100.0, 0.5) } + p(1050.0, 1.0)
        val tl = ReplayTimeline.build(2200.0, many)
        assertTrue(tl.playDurationSec <= 60.0, "play=${tl.playDurationSec}")
        assertTrue(tl.segments.any { it.speed == 1.0 && 1050.0 in it.startT..it.endT })
    }

    @Test fun `재생 시간과 주행 시간은 서로 변환된다`() {
        val tl = ReplayTimeline.build(92.0, listOf(p(15.0), p(30.0)))
        for (t in listOf(0.0, 5.0, 15.0, 22.0, 30.0, 60.0, 92.0)) assertEquals(t, tl.tripTimeAt(tl.playTimeOf(t)), 1e-9)
        assertEquals(92.0, tl.tripTimeAt(9999.0))
    }

    @Test fun `길이가 0이면 빈 시간표다`() {
        val tl = ReplayTimeline.build(0.0, listOf(p(0.0)))
        assertTrue(tl.segments.isEmpty())
        assertEquals(0.0, tl.playDurationSec)
    }

    @Test fun `가장 가까운 감점을 고르고 창 밖이면 없다`() {
        val ps = listOf(p(10.0), p(16.0))
        assertEquals(16.0, activePenalty(ps, 14.0)?.t)
        assertNull(activePenalty(ps, 30.0))
    }
}

class BadgesTest {
    private val turn = ItemScore(Category.TURN, 25.0, 25.0, 5, false)
    private val lane = ItemScore(Category.LANE_CHANGE, 20.0, 0.0, 1, false)
    private val missing = Penalty(Category.LANE_CHANGE, PenaltyReason.SIGNAL_MISSING, 1.0, 1.0, 1.0)

    @Test fun `시연처럼 차선변경만 놓치면 회전 배지만 받는다`() {
        assertEquals(listOf(Badge.TURN_PERFECT), BadgeRules.award(51, listOf(turn, lane), listOf(missing), 985.0, listOf(60)))
    }

    @Test fun `무감점이고 최고 기록이면 모두 받는다`() {
        assertEquals(Badge.entries.toSet(), BadgeRules.award(100, listOf(turn), emptyList(), 985.0, listOf(60)).toSet())
    }

    @Test fun `첫 주행은 최고 기록이 아니고 짧은 이동은 배지가 없다`() {
        assertFalse(Badge.PERSONAL_BEST in BadgeRules.award(100, listOf(turn), emptyList(), 985.0, emptyList()))
        assertTrue(BadgeRules.award(100, listOf(turn), emptyList(), 126.0, listOf(60)).isEmpty())
    }
}
