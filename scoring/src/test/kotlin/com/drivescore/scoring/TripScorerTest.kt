package com.drivescore.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripScorerTest {
    private val scorer = TripScorer(CFG)

    private fun lane(lead: Double, cancel: Boolean = true) = trip {
        hold(10.0, 50.0)
        signal(TurnSignal.RIGHT); hold(lead, 50.0)
        ramp(1.5, 50.0, 50.0, 0.0, 25.0); ramp(1.5, 50.0, 50.0, 25.0, 0.0)
        if (cancel) signal(TurnSignal.OFF)
        hold(10.0, 50.0)
    }

    @Test fun `늦은 깜빡이는 늦음 감점이고 미사용보다 가볍다`() {
        val late = scorer.score(lane(1.0).samples, rules = SKILLED)
        val p = late.penalties.single { it.category == Category.LANE_CHANGE }
        assertEquals(PenaltyReason.SIGNAL_LATE, p.reason)
        assertTrue(p.severity < CFG.severityMissing)
        assertEquals(1.0, p.observed.getValue("leadSec"), 0.15)
    }

    @Test fun `반대 방향 깜빡이는 미사용`() {
        val b = trip {
            hold(10.0, 50.0)
            signal(TurnSignal.LEFT); hold(5.0, 50.0)
            ramp(1.5, 50.0, 50.0, 0.0, 25.0); ramp(1.5, 50.0, 50.0, 25.0, 0.0)
            signal(TurnSignal.OFF); hold(5.0, 50.0)
        }
        assertTrue(scorer.score(b.samples, rules = BEGINNER).penalties.any { it.reason == PenaltyReason.SIGNAL_MISSING })
    }

    @Test fun `차선변경 후 깜빡이 미해제 감점`() {
        val r = scorer.score(lane(5.0, cancel = false).samples, rules = BEGINNER)
        assertTrue(r.penalties.any { it.reason == PenaltyReason.SIGNAL_NOT_CANCELLED })
    }

    @Test fun `차선변경 직후 3초 내 급제동은 간격 부족 감점`() {
        val b = trip {
            hold(10.0, 60.0)
            signal(TurnSignal.RIGHT); hold(5.0, 60.0)
            ramp(1.5, 60.0, 60.0, 0.0, 25.0); ramp(1.5, 60.0, 60.0, 25.0, 0.0)
            signal(TurnSignal.OFF)
            hold(1.0, 60.0)
            ramp(2.5, 60.0, 10.0, brake = true)
            hold(5.0, 10.0)
        }
        val r = scorer.score(b.samples, rules = BEGINNER)
        assertTrue(r.penalties.any { it.reason == PenaltyReason.HARD_BRAKE_AFTER_LANE_CHANGE })
    }

    @Test fun `기준 근처에서 끊긴 감속은 급정지 한 건이고 멀리 떨어지면 두 건이다`() {
        fun rate(t: Double) = if (t in 5.0..6.5 || t in 7.5..9.0 || t in 30.0..31.5) -6.0 else 0.0
        var speed = 120.0
        var odo = 0.0
        val samples = (0..400).map { i ->
            val t = i * 0.1
            speed = (speed + rate(t) * 3.6 * 0.1).coerceAtLeast(0.0)
            odo += speed / 3.6 * 0.1
            SignalSample(t, odo, speed, 0.0, TurnSignal.OFF, rate(t) < 0, Gear.D, null, null)
        }
        assertEquals(2, scorer.score(samples, rules = BEGINNER).penalties.count { it.reason == PenaltyReason.HARD_BRAKE })
    }

    @Test fun `페달을 안 밟은 감속은 급정지가 아니다`() {
        val b = trip { hold(5.0, 60.0); ramp(2.5, 60.0, 0.0, brake = false); hold(3.0, 0.0) }
        assertFalse(scorer.score(b.samples, rules = BEGINNER).penalties.any { it.reason == PenaltyReason.HARD_BRAKE })
    }

    @Test fun `브레이크 미지원 차량은 감속도만으로 급정지 판정`() {
        val b = trip { hold(5.0, 60.0); ramp(2.5, 60.0, 0.0, brake = false); hold(3.0, 0.0) }
        val r = scorer.score(b.samples, capabilities = SignalCapabilities(brake = false), rules = BEGINNER)
        assertTrue(r.penalties.any { it.reason == PenaltyReason.HARD_BRAKE })
    }

    @Test fun `급출발 감점과 완만한 가속 구분`() {
        val hard = trip { hold(2.0, 0.0); ramp(2.0, 0.0, 50.0); hold(5.0, 50.0) }
        val soft = trip { hold(2.0, 0.0); ramp(10.0, 0.0, 50.0); hold(5.0, 50.0) }
        assertTrue(scorer.score(hard.samples, rules = BEGINNER).penalties.any { it.reason == PenaltyReason.HARD_ACCEL })
        assertFalse(scorer.score(soft.samples, rules = BEGINNER).penalties.any { it.reason == PenaltyReason.HARD_ACCEL })
    }

    @Test fun `과속카메라 지점 제한속도 초과 감점`() {
        val b = trip { hold(60.0, 70.0) }
        val cam = RouteMarker("c", MarkerType.SPEED_CAMERA, 500.0, 50)
        val r = scorer.score(b.samples, listOf(cam), rules = BEGINNER)
        assertTrue(r.penalties.any { it.reason == PenaltyReason.OVER_SPEED })
        val ok = scorer.score(trip { hold(60.0, 45.0) }.samples, listOf(cam), rules = BEGINNER)
        assertFalse(ok.penalties.any { it.category == Category.CAMERA })
    }

    @Test fun `구간단속은 평균 속도로 판정`() {
        val b = trip { hold(30.0, 100.0); hold(30.0, 60.0); hold(10.0, 60.0) }
        val start = RouteMarker("s", MarkerType.SECTION_START, 0.0, 80)
        val end = RouteMarker("e", MarkerType.SECTION_END, b.samples[599].odoM, null)
        // 평균 80km/h, 순간 100km/h. 초과가 아니어야 한다.
        val r = scorer.score(b.samples, listOf(start, end), rules = BEGINNER)
        assertFalse(r.penalties.any { it.category == Category.CAMERA })
    }

    @Test fun `회전 지점 앞 늦은 감속 감점`() {
        val b = trip {
            hold(30.0, 70.0)
            signal(TurnSignal.RIGHT); ramp(3.0, 70.0, 20.0)
            ramp(2.0, 20.0, 20.0, 0.0, 200.0); ramp(2.0, 20.0, 20.0, 200.0, 0.0)
            signal(TurnSignal.OFF); hold(5.0, 20.0)
        }
        val turnOdo = b.samples.first { it.steeringDeg!! > 5 }.odoM
        val r = scorer.score(b.samples, listOf(RouteMarker("t", MarkerType.TURN_RIGHT, turnOdo, null)), rules = BEGINNER)
        assertTrue(r.penalties.any { it.reason == PenaltyReason.LATE_DECEL })
    }

    @Test fun `지나친 저속은 가점 없이 감속 준비 항목 감점`() {
        val slow = trip { hold(120.0, 10.0) }
        val r = scorer.score(slow.samples, rules = BEGINNER)
        assertTrue(r.penalties.any { it.reason == PenaltyReason.TOO_SLOW && it.category == Category.DECEL_PREP })
        assertTrue(r.total < 100)
    }

    @Test fun `이벤트 없는 항목은 만점 + 해당 없음`() {
        val r = scorer.score(trip { hold(60.0, 50.0) }.samples, rules = BEGINNER)
        val turn = r.items.single { it.category == Category.TURN }
        assertTrue(turn.notApplicable)
        assertEquals(turn.maxPoints, turn.points, 1e-9)
        assertEquals(100, r.total)
    }

    @Test fun `어려운 경로일수록 같은 감점의 최종 손실이 줄어든다`() {
        val b = trip {
            hold(10.0, 50.0)
            ramp(1.5, 50.0, 50.0, 0.0, 30.0); ramp(1.5, 50.0, 50.0, 30.0, 0.0)
            hold(600.0, 50.0)
        }
        val easy = scorer.score(b.samples, rules = BEGINNER)
        val cams = (1..20).map { RouteMarker("c$it", MarkerType.SPEED_CAMERA, 500.0 + it * 300, 80) }
        val hard = scorer.score(b.samples, cams, rules = BEGINNER)
        assertTrue(hard.difficulty > easy.difficulty)
        assertTrue(hard.total >= easy.total)
        assertTrue(hard.difficulty <= 1.0 + CFG.difficultyMaxBonus)
    }

    @Test fun `조향 미지원이면 관련 항목을 빼고 배점을 100으로 재분배`() {
        val r = scorer.score(trip { hold(60.0, 50.0) }.samples, capabilities = SignalCapabilities(steering = false), rules = BEGINNER)
        val cats = r.items.map { it.category }.toSet()
        assertFalse(Category.TURN in cats || Category.LANE_CHANGE in cats || Category.STRAIGHT in cats)
        assertEquals(100.0, r.items.sumOf { it.maxPoints }, 1e-6)
    }

    @Test fun `깜빡이 미지원이면 깜빡이 감점을 하지 않는다`() {
        val b = trip {
            hold(10.0, 50.0)
            ramp(1.5, 50.0, 50.0, 0.0, 30.0); ramp(1.5, 50.0, 50.0, 30.0, 0.0)
            hold(10.0, 50.0)
        }
        val r = scorer.score(b.samples, capabilities = SignalCapabilities(turnSignal = false), rules = BEGINNER)
        assertFalse(r.penalties.any { it.reason == PenaltyReason.SIGNAL_MISSING })
    }

    @Test fun `마스터는 회전 후 깜빡이를 즉시 해제해야 한다`() {
        fun drive() = trip {
            hold(10.0, 20.0)
            signal(TurnSignal.RIGHT); hold(5.0, 15.0)
            ramp(2.0, 15.0, 15.0, 0.0, 200.0); ramp(2.0, 15.0, 15.0, 200.0, 0.0)
            hold(10.0, 20.0)
        }
        assertTrue(scorer.score(drive().samples, rules = MASTER).penalties.any { it.reason == PenaltyReason.SIGNAL_NOT_CANCELLED })
        assertFalse(scorer.score(drive().samples, rules = VETERAN).penalties.any { it.reason == PenaltyReason.SIGNAL_NOT_CANCELLED })
    }

    @Test fun `빈 입력은 안전하게 100점`() {
        assertEquals(100, scorer.score(emptyList(), rules = BEGINNER).total)
    }
}
