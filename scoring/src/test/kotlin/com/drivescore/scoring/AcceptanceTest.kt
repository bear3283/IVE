package com.drivescore.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TASKS.md M1 완료 기준 7개. */
class AcceptanceTest {
    private fun score(b: TripBuilder, markers: List<RouteMarker> = emptyList()) =
        TripScorer(CFG).score(b.samples, markers)

    @Test fun `1 깜빡이 없이 30도 꺾었다 되돌리면 차선변경 + 깜빡이 미사용 감점`() {
        val b = trip {
            hold(10.0, 50.0)
            ramp(1.5, 50.0, 50.0, 0.0, 30.0)
            ramp(1.5, 50.0, 50.0, 30.0, 0.0)
            hold(10.0, 50.0)
        }
        val r = score(b)
        assertEquals(1, r.maneuvers.count { it.type == ManeuverType.LANE_CHANGE })
        assertTrue(r.penalties.any { it.category == Category.SIGNAL && it.reason == PenaltyReason.SIGNAL_MISSING })
    }

    @Test fun `2 깜빡이 30m 전 + 200도 저속이면 우회전이고 감점 없음`() {
        val b = trip {
            hold(10.0, 20.0)
            signal(TurnSignal.RIGHT)
            hold(8.0, 15.0)
            ramp(2.0, 15.0, 15.0, 0.0, 200.0)
            ramp(2.0, 15.0, 15.0, 200.0, 0.0)
            signal(TurnSignal.OFF)
            hold(10.0, 20.0)
        }
        val r = score(b)
        assertEquals(1, r.maneuvers.count { it.type == ManeuverType.TURN_RIGHT })
        assertEquals(emptyList(), r.penalties)
        assertEquals(100, r.total)
    }

    private fun shake15() = trip {
        hold(10.0, 50.0)
        hold(0.75, 50.0, 15.0)
        hold(0.75, 50.0, -15.0)
        hold(10.0, 50.0)
    }

    @Test fun `3 50kmh 직선 ±15도 1_5초는 흔들림이고 차선변경이 아니다`() {
        assertTrue(score(shake15()).penalties.any { it.category == Category.STEERING })
        assertEquals(0, score(shake15()).maneuvers.count { it.type == ManeuverType.LANE_CHANGE })
    }

    @Test fun `4 페달 ON + 감속도 기준 초과면 급정지 감점`() {
        val b = trip {
            hold(10.0, 60.0)
            ramp(2.5, 60.0, 0.0, brake = true)
            hold(3.0, 0.0, brake = true)
        }
        assertTrue(score(b).penalties.any { it.reason == PenaltyReason.HARD_BRAKE })
    }

    @Test fun `6 같은 방향 20도 10초 유지 커브는 감점 없음`() {
        val b = trip {
            hold(10.0, 60.0)
            ramp(0.5, 60.0, 60.0, 0.0, 20.0)
            hold(10.0, 60.0, 20.0)
            ramp(0.5, 60.0, 60.0, 20.0, 0.0)
            hold(10.0, 60.0)
        }
        val r = score(b)
        assertEquals(1, r.maneuvers.count { it.type == ManeuverType.CURVE })
        assertEquals(emptyList(), r.penalties)
    }

    @Test fun `7 오늘은 아내가 운전해 는 SWITCH_DRIVER`() {
        assertEquals(Intent.SWITCH_DRIVER, IntentMatcher.match("오늘은 아내가 운전해")?.intent)
    }
}
