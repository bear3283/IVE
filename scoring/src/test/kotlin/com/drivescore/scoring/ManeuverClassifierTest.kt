package com.drivescore.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManeuverClassifierTest {
    private fun classify(b: TripBuilder, rules: GradeRules = SKILLED, markers: List<RouteMarker> = emptyList()) =
        ManeuverClassifier(CFG).classify(b.samples, markers, rules)

    @Test fun `왼쪽 회전과 유턴 구분`() {
        val left = trip { hold(5.0, 15.0); ramp(2.0, 15.0, 15.0, 0.0, -200.0); ramp(2.0, 15.0, 15.0, -200.0, 0.0); hold(5.0, 15.0) }
        assertTrue(classify(left).any { it.type == ManeuverType.TURN_LEFT })
        val u = trip { hold(5.0, 10.0); ramp(2.0, 10.0, 10.0, 0.0, -500.0); ramp(2.0, 10.0, 10.0, -500.0, 0.0); hold(5.0, 10.0) }
        assertTrue(classify(u).any { it.type == ManeuverType.U_TURN })
    }

    @Test fun `부호 반전 1회 S자 차선변경`() {
        val b = trip {
            hold(5.0, 60.0)
            ramp(1.0, 60.0, 60.0, 0.0, 20.0); ramp(2.0, 60.0, 60.0, 20.0, -20.0); ramp(1.0, 60.0, 60.0, -20.0, 0.0)
            hold(5.0, 60.0)
        }
        assertEquals(1, classify(b).count { it.type == ManeuverType.LANE_CHANGE })
    }

    @Test fun `반복 흔들림은 WOBBLE`() {
        val b = trip {
            hold(5.0, 60.0)
            repeat(4) { hold(0.6, 60.0, 12.0); hold(0.6, 60.0, -12.0) }
            hold(5.0, 60.0)
        }
        assertEquals(1, classify(b).count { it.type == ManeuverType.WOBBLE })
    }

    @Test fun `저속에서 차선변경 모양은 차선변경이 아니다`() {
        val b = trip { hold(5.0, 15.0); ramp(1.5, 15.0, 15.0, 0.0, 25.0); ramp(1.5, 15.0, 15.0, 25.0, 0.0); hold(5.0, 15.0) }
        assertEquals(0, classify(b).count { it.type == ManeuverType.LANE_CHANGE })
    }

    @Test fun `모호한 70도는 내비 회전 지점 근처면 회전`() {
        fun b() = trip { hold(5.0, 20.0); ramp(2.0, 20.0, 20.0, 0.0, 70.0); ramp(2.0, 20.0, 20.0, 70.0, 0.0); hold(5.0, 20.0) }
        assertEquals(0, classify(b()).count { it.type == ManeuverType.TURN_RIGHT })
        val odo = b().samples.first { it.steeringDeg!! > 5 }.odoM
        assertEquals(1, classify(b(), markers = listOf(RouteMarker("t", MarkerType.TURN_RIGHT, odo, null))).count { it.type == ManeuverType.TURN_RIGHT })
    }

    @Test fun `정차 중 조향은 동작이 아니다`() {
        val b = trip { hold(5.0, 0.0); ramp(2.0, 0.0, 0.0, 0.0, 300.0); ramp(2.0, 0.0, 0.0, 300.0, 0.0); hold(5.0, 0.0) }
        assertTrue(classify(b, MASTER).all { it.type == ManeuverType.STRAIGHT })
    }

    @Test fun `steeringSign으로 부호를 뒤집는다`() {
        val b = trip { hold(5.0, 15.0); ramp(2.0, 15.0, 15.0, 0.0, 200.0); ramp(2.0, 15.0, 15.0, 200.0, 0.0); hold(5.0, 15.0) }
        val r = ManeuverClassifier(CFG.copy(steeringSign = -1)).classify(b.samples, emptyList(), SKILLED)
        assertTrue(r.any { it.type == ManeuverType.TURN_LEFT })
    }

    @Test fun `구간은 시간 순으로 빈틈없이 이어진다`() {
        val m = classify(trip { hold(5.0, 15.0); ramp(2.0, 15.0, 15.0, 0.0, 200.0); ramp(2.0, 15.0, 15.0, 200.0, 0.0); hold(5.0, 15.0) })
        m.zipWithNext { a, b -> assertEquals(a.endT, b.startT, 0.11) }
    }
}
