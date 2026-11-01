package com.drivescore.domain

import com.drivescore.scoring.*
import kotlin.math.abs

/** 주행 중 파일에 한 줄씩 쌓는 형식. 앱이 죽어도 남은 줄로 복구한다. */
object RecordingCodec {
    data class Decoded(val samples: List<SignalSample>, val markers: List<RouteMarker>)

    fun encode(s: SignalSample) = listOf(
        "S", s.t, s.odoM, s.speedKph, s.steeringDeg ?: "", s.turnSignal ?: "", s.brakeOn ?: "", s.gear, s.lat ?: "", s.lng ?: "",
    ).joinToString(",")

    fun encode(m: RouteMarker) = listOf("M", m.id.replace(",", "_"), m.type, m.odoM, m.speedLimitKph ?: "").joinToString(",")

    /** 강제 종료로 잘린 줄은 건너뛴다. 같은 id의 지점은 마지막 값을 쓴다. */
    fun decode(lines: List<String>): Decoded {
        val samples = mutableListOf<SignalSample>()
        val markers = linkedMapOf<String, RouteMarker>()
        for (line in lines) {
            val p = line.split(",")
            runCatching {
                when {
                    p[0] == "S" && p.size == 10 -> samples += SignalSample(
                        t = p[1].toDouble(), odoM = p[2].toDouble(), speedKph = p[3].toDouble(),
                        steeringDeg = p[4].toDoubleOrNull(),
                        turnSignal = p[5].takeIf { it.isNotEmpty() }?.let(TurnSignal::valueOf),
                        brakeOn = p[6].toBooleanStrictOrNull(),
                        gear = Gear.valueOf(p[7]),
                        lat = p[8].toDoubleOrNull(), lng = p[9].toDoubleOrNull(),
                    )
                    p[0] == "M" && p.size == 5 -> markers[p[1]] =
                        RouteMarker(p[1], MarkerType.valueOf(p[2]), p[3].toDouble(), p[4].toIntOrNull())
                }
            }
        }
        return Decoded(samples, markers.values.toList())
    }
}

object MarkerMerger {
    private const val NEAR_M = 50.0

    /** id가 같거나, 회전 지점이 같은 타입으로 가까우면 같은 지점으로 보고 새 값으로 바꾼다. */
    fun merge(current: List<RouteMarker>, incoming: List<RouteMarker>): List<RouteMarker> {
        val out = current.toMutableList()
        for (m in incoming) {
            val i = out.indexOfFirst { it.id == m.id || (isTurn(it) && it.type == m.type && abs(it.odoM - m.odoM) < NEAR_M) }
            if (i >= 0) out[i] = m else out += m
        }
        return out.sortedBy { it.odoM }
    }

    private fun isTurn(m: RouteMarker) =
        m.type == MarkerType.TURN_LEFT || m.type == MarkerType.TURN_RIGHT || m.type == MarkerType.U_TURN
}

object TrackReducer {
    /** 전체는 1Hz, 감점 전후 [windowSec]초는 원본 해상도로 남긴다. 리플레이 오버레이용. */
    fun reduce(samples: List<SignalSample>, penalties: List<Penalty>, windowSec: Double = 10.0): List<SignalSample> {
        var nextSecond = Double.NEGATIVE_INFINITY
        return samples.filter { s ->
            val keepSecond = s.t >= nextSecond
            if (keepSecond) nextSecond = s.t + 1.0 - 1e-6
            keepSecond || penalties.any { abs(it.t - s.t) <= windowSec }
        }
    }
}
