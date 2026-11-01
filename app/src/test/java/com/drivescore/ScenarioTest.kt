package com.drivescore

import com.drivescore.data.signal.Scenario
import com.drivescore.data.signal.ScenarioPlayer
import com.drivescore.scoring.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenarioTest {
    private fun load(name: String): Scenario {
        val f = listOf("src/main/assets/scenarios/$name", "app/src/main/assets/scenarios/$name").map(::File).first { it.exists() }
        return Scenario.parse(f.readText())
    }

    private fun samples(p: ScenarioPlayer): List<SignalSample> {
        val out = mutableListOf<SignalSample>()
        var t = 0.0
        var odo = 0.0
        while (t <= p.durationSec) {
            val s = p.stateAt(t)
            out += SignalSample(t, odo, s.speedKph ?: 0.0, s.steeringDeg, s.turnSignal, s.brakeOn, s.gear ?: Gear.P, s.lat, s.lng)
            odo += (s.speedKph ?: 0.0) / 3.6 * 0.1
            t += 0.1
        }
        return out
    }

    @Test fun `값 사이는 선형 보간하고 기어와 깜빡이는 직전 값을 유지`() {
        val p = ScenarioPlayer(Scenario.parse("""{"name":"x","destination":{"name":"d","lat":1.0,"lng":2.0},"samples":[
            {"t":0,"speedKph":0,"lat":1.0,"lng":2.0,"gear":"D","turnSignal":"LEFT"},
            {"t":10,"speedKph":100,"lat":1.0,"lng":2.0,"steeringDeg":20,"turnSignal":"HAZARD","brakePct":40,"gear":"P"}]}"""))
        val s = p.stateAt(5.0)
        assertEquals(50.0, s.speedKph!!, 1e-9)
        assertEquals(Gear.D, s.gear)
        assertEquals(TurnSignal.LEFT, s.turnSignal)
        val end = p.stateAt(99.0)
        assertEquals(Gear.P, end.gear)
        assertEquals(TurnSignal.OFF, end.turnSignal) // HAZARD는 OFF
        assertEquals(true, end.brakeOn) // brakePct > 0
    }

    @Test fun `마지막 샘플은 P이고 시간이 증가한다`() {
        listOf("demo_short.json", "commute.json").forEach {
            val s = load(it)
            assertEquals("P", s.samples.last().gear ?: s.samples.lastOrNull { x -> x.gear != null }?.gear, it)
            s.samples.zipWithNext { a, b -> assertTrue(a.t < b.t) }
        }
    }

    @Test fun `시연 시나리오는 의도한 감점만 낸다`() {
        val p = ScenarioPlayer(load("demo_short.json"))
        val r = TripScorer().score(samples(p), p.markers())
        val types = r.maneuvers.map { it.type }
        assertEquals(1, types.count { it == ManeuverType.LANE_CHANGE }, "$types")
        assertEquals(1, types.count { it == ManeuverType.WOBBLE }, "$types")
        assertEquals(1, types.count { it == ManeuverType.TURN_RIGHT }, "$types")
        val reasons = r.penalties.map { it.reason }
        assertTrue(PenaltyReason.SIGNAL_MISSING in reasons, "$reasons")
        assertTrue(PenaltyReason.STEERING_WOBBLE in reasons, "$reasons")
        assertTrue(PenaltyReason.HARD_BRAKE in reasons, "$reasons")
        assertEquals(setOf(PenaltyReason.SIGNAL_MISSING, PenaltyReason.STEERING_WOBBLE, PenaltyReason.HARD_BRAKE), reasons.toSet(), "$reasons")
        assertTrue(p.durationSec in 90.0..130.0, "duration=${p.durationSec}")
        // 깜빡이 미사용 8 + 흔들림 5 + 급정지 6
        assertEquals(81, r.total, "${r.penalties}")
    }

    @Test fun `샘플링 위상이 달라도 시연 감점 결과는 같다`() {
        val p = ScenarioPlayer(load("demo_short.json"))
        for (phase in listOf(0.0, 0.03, 0.05, 0.07)) {
            val out = mutableListOf<SignalSample>()
            var t = phase
            var odo = 0.0
            while (t <= p.durationSec) {
                val s = p.stateAt(t)
                out += SignalSample(t, odo, s.speedKph ?: 0.0, s.steeringDeg, s.turnSignal, s.brakeOn, s.gear ?: Gear.P)
                odo += (s.speedKph ?: 0.0) / 3.6 * 0.1
                t += 0.1
            }
            val reasons = TripScorer().score(out, p.markers()).penalties.map { it.reason }.toSet()
            assertEquals(setOf(PenaltyReason.SIGNAL_MISSING, PenaltyReason.STEERING_WOBBLE, PenaltyReason.HARD_BRAKE), reasons, "phase=$phase")
        }
    }

    @Test fun `출근 시나리오는 회전 4번 이상이고 약 10분이다`() {
        val p = ScenarioPlayer(load("commute.json"))
        val r = TripScorer().score(samples(p), p.markers())
        val turns = r.maneuvers.count { it.type == ManeuverType.TURN_LEFT || it.type == ManeuverType.TURN_RIGHT }
        assertTrue(turns >= 4, "turns=$turns")
        assertTrue(p.durationSec in 400.0..720.0, "duration=${p.durationSec}")
        assertTrue(r.total in 50..99, "total=${r.total} ${r.penalties.map { it.reason }}")
    }

    @Test fun `안내 지점은 경로 누적거리로 변환된다`() {
        val m = ScenarioPlayer(load("demo_short.json")).markers()
        m.zipWithNext { a, b -> assertTrue(a.odoM <= b.odoM, "${a.id} ${b.id}") }
        assertTrue(m.first().odoM > 100)
    }
}
