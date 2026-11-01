package com.drivescore.scoring

import kotlin.math.abs
import kotlin.math.sign

/** 샘플 스트림을 동작 구간으로 나눈다. 우선순위: 유턴 > 회전 > 차선변경 > 커브 > 흔들림 > 직선. */
class ManeuverClassifier(private val config: ScoringConfig) {

    fun classify(samples: List<SignalSample>, markers: List<RouteMarker>, rules: GradeRules): List<Maneuver> {
        if (samples.isEmpty()) return emptyList()
        val detected = findExcursions(samples).mapNotNull { (from, to) ->
            classifyExcursion(samples.subList(from, to + 1), markers, rules)
        }
        return fillWithStraight(samples, detected)
    }

    private fun steer(s: SignalSample) = (s.steeringDeg ?: 0.0) * config.steeringSign

    /** |조향| >= centerDeg 인 구간의 인덱스 범위. 짧게 0을 지나는 것은 한 구간으로 합친다. */
    private fun findExcursions(samples: List<SignalSample>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var last = -1
        samples.forEachIndexed { i, s ->
            if (abs(steer(s)) >= config.centerDeg) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && s.t - samples[last].t >= config.settleSec) {
                out += start to last
                start = -1
            }
        }
        if (start >= 0) out += start to last
        return out
    }

    private fun classifyExcursion(seg: List<SignalSample>, markers: List<RouteMarker>, rules: GradeRules): Maneuver? {
        if (seg.maxOf { it.speedKph } < config.ignoreBelowKph) return null
        val angles = seg.map(::steer)
        val peakAbs = angles.maxOf { abs(it) }
        val peak = angles.first { abs(it) == peakAbs }
        val direction = angles.first { it != 0.0 }.sign.toInt()
        val duration = seg.last().t - seg.first().t
        val minSpeed = seg.minOf { it.speedKph }
        val avgSpeed = seg.map { it.speedKph }.average()
        val reversals = countReversals(angles, config.centerDeg)

        fun near(vararg types: MarkerType) = markers.any {
            it.type in types &&
                it.odoM >= seg.first().odoM - config.naviMatchM &&
                it.odoM <= seg.last().odoM + config.naviMatchM
        }
        fun maneuver(type: ManeuverType) = Maneuver(type, seg.first().t, seg.last().t, seg.first().odoM, seg.last().odoM, peak, direction)
        val turnType = if (peak > 0) ManeuverType.TURN_RIGHT else ManeuverType.TURN_LEFT
        val nearTurn = near(if (peak > 0) MarkerType.TURN_RIGHT else MarkerType.TURN_LEFT)

        return when {
            minSpeed <= config.uTurnMaxKph &&
                (peakAbs >= config.uTurnMinDeg || (peakAbs >= config.uTurnNearMarkerMinDeg && near(MarkerType.U_TURN))) ->
                maneuver(ManeuverType.U_TURN)

            minSpeed <= config.turnMaxKph &&
                (peakAbs >= config.turnMinDeg || (peakAbs >= config.turnNearMarkerMinDeg && nearTurn)) ->
                maneuver(turnType)

            peakAbs >= config.laneChangeMinDeg && peakAbs <= config.laneChangeMaxDeg &&
                duration >= config.laneChangeMinSec && duration <= config.laneChangeMaxSec &&
                reversals <= config.laneChangeMaxReversals &&
                avgSpeed >= config.laneChangeMinKph ->
                maneuver(ManeuverType.LANE_CHANGE)

            duration >= config.curveMinSec && peakAbs < config.curveMaxDeg && reversals == 0 ->
                maneuver(ManeuverType.CURVE)

            isWobble(seg, angles, rules) -> maneuver(ManeuverType.WOBBLE)

            else -> null
        }
    }

    private fun isWobble(seg: List<SignalSample>, angles: List<Double>, rules: GradeRules): Boolean {
        val tolerance = rules.straightToleranceDeg
        var overSec = 0.0
        for (i in 1 until seg.size) {
            if (abs(angles[i]) > tolerance) overSec += seg[i].t - seg[i - 1].t
        }
        return overSec >= config.wobbleMinSec - EPS ||
            countReversals(angles, tolerance) >= config.wobbleMinReversals
    }

    /** minAbs를 넘는 값들 사이의 부호 전환 횟수. */
    private fun countReversals(angles: List<Double>, minAbs: Double): Int {
        var count = 0
        var last = 0.0
        for (a in angles) {
            if (abs(a) < minAbs) continue
            if (last != 0.0 && a.sign != last) count++
            last = a.sign
        }
        return count
    }

    private fun fillWithStraight(samples: List<SignalSample>, detected: List<Maneuver>): List<Maneuver> {
        val out = mutableListOf<Maneuver>()
        var t = samples.first().t
        var odo = samples.first().odoM
        for (m in detected) {
            if (m.startT > t) out += Maneuver(ManeuverType.STRAIGHT, t, m.startT, odo, m.startOdoM)
            out += m
            t = m.endT
            odo = m.endOdoM
        }
        val last = samples.last()
        if (last.t > t) out += Maneuver(ManeuverType.STRAIGHT, t, last.t, odo, last.odoM)
        return out
    }

    private companion object {
        const val EPS = 1e-6
    }
}
