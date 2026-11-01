package com.drivescore.scoring

import kotlin.math.abs
import kotlin.math.sign

/** 샘플 스트림을 동작 구간으로 나눈다. 우선순위: 유턴 > 회전 > 차선변경 > 커브 > 흔들림 > 직선. */
class ManeuverClassifier(private val config: ScoringConfig) {

    fun classify(samples: List<SignalSample>, markers: List<RouteMarker>): List<Maneuver> {
        if (samples.isEmpty()) return emptyList()
        val detected = findExcursions(samples).mapNotNull { (from, to) ->
            classifyExcursion(samples.subList(from, to + 1), markers)
        }
        return fillWithStraight(samples, detected)
    }

    private fun steer(s: SignalSample) = (s.steeringDeg ?: 0.0) * config.steeringSign

    private fun centerDeg(speedKph: Double) =
        config.steeringDegFor(speedKph, config.centerLateralMps2).coerceIn(config.centerMinDeg, config.centerDeg)

    /** 같은 각도라도 빠를수록 차가 크게 움직이므로 허용 각도를 속도에 맞춘다. */
    private fun wobbleDeg(speedKph: Double) =
        config.steeringDegFor(speedKph, config.wobbleLateralMps2).coerceIn(config.wobbleMinDeg, config.wobbleMaxDeg)

    /** 조향이 속도별 기준 이상인 구간의 인덱스 범위. 짧게 0을 지나는 것은 한 구간으로 합친다. */
    private fun findExcursions(samples: List<SignalSample>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var last = -1
        samples.forEachIndexed { i, s ->
            if (abs(steer(s)) >= centerDeg(s.speedKph)) {
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

    private fun classifyExcursion(seg: List<SignalSample>, markers: List<RouteMarker>): Maneuver? {
        if (seg.maxOf { it.speedKph } < config.ignoreBelowKph) return null
        val angles = seg.map(::steer)
        val peakAbs = angles.maxOf { abs(it) }
        val peak = angles.first { abs(it) == peakAbs }
        val direction = angles.first { it != 0.0 }.sign.toInt()
        val duration = seg.last().t - seg.first().t
        val minSpeed = seg.minOf { it.speedKph }
        val avgSpeed = seg.map { it.speedKph }.average()
        val reversals = countReversals(seg, angles, ::centerDeg)
        // 고속에서는 차선변경 각도가 작다. 작은 각도는 커브와 구분되는 S자 조향만 차선변경으로 본다.
        val laneMinDeg = if (reversals == 1) {
            config.steeringDegFor(avgSpeed, config.laneChangeLateralMps2).coerceIn(config.laneChangeFloorDeg, config.laneChangeMinDeg)
        } else {
            config.laneChangeMinDeg
        }

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

            peakAbs >= laneMinDeg && peakAbs <= config.laneChangeMaxDeg &&
                duration >= config.laneChangeMinSec && duration <= config.laneChangeMaxSec &&
                reversals <= config.laneChangeMaxReversals &&
                avgSpeed >= config.laneChangeMinKph ->
                maneuver(ManeuverType.LANE_CHANGE)

            duration >= config.curveMinSec && peakAbs < config.curveMaxDeg && reversals == 0 ->
                maneuver(ManeuverType.CURVE)

            isWobble(seg, angles) -> maneuver(ManeuverType.WOBBLE)

            else -> null
        }
    }

    /** 방향이 바뀌지 않는 조향은 도로를 따라간 것으로 보고 흔들림에서 뺀다. */
    private fun isWobble(seg: List<SignalSample>, angles: List<Double>): Boolean {
        val reversals = countReversals(seg, angles, ::wobbleDeg)
        if (reversals == 0) return false
        var overSec = 0.0
        for (i in 1 until seg.size) {
            if (abs(angles[i]) > wobbleDeg(seg[i].speedKph)) overSec += seg[i].t - seg[i - 1].t
        }
        return overSec >= config.wobbleMinSec - EPS || reversals >= config.wobbleMinReversals
    }

    /** 속도별 기준을 넘는 값들 사이의 부호 전환 횟수. */
    private fun countReversals(seg: List<SignalSample>, angles: List<Double>, minAbs: (Double) -> Double): Int {
        var count = 0
        var last = 0.0
        angles.forEachIndexed { i, a ->
            if (abs(a) >= minAbs(seg[i].speedKph)) {
                if (last != 0.0 && a.sign != last) count++
                last = a.sign
            }
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
