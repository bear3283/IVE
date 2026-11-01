package com.drivescore.data.signal

import com.drivescore.scoring.Gear
import com.drivescore.scoring.MarkerType
import com.drivescore.scoring.RouteMarker
import com.drivescore.scoring.TurnSignal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.cos

/** docs/REPLAY_FORMAT.md */
@Serializable
data class Scenario(
    val name: String,
    val destination: ScenarioDestination,
    val playbackSpeed: Double = 1.0,
    val samples: List<ScenarioSample>,
    val naviPoints: List<ScenarioPoint> = emptyList(),
    val scriptedEvents: List<ScriptedEvent> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Scenario = json.decodeFromString(serializer(), text)
    }
}

@Serializable data class ScenarioDestination(val name: String, val lat: Double, val lng: Double)

@Serializable
data class ScenarioSample(
    val t: Double,
    val speedKph: Double = 0.0,
    val lat: Double,
    val lng: Double,
    val gear: String? = null,
    val steeringDeg: Double? = null,
    val turnSignal: String? = null,
    val brakePct: Double? = null,
)

@Serializable
data class ScenarioPoint(val id: String, val type: String, val lat: Double, val lng: Double, val speedLimitKph: Int? = null)

@Serializable data class ScriptedEvent(val t: Double, val note: String)

/** 시나리오 시간 → 차량 상태. 연속값은 선형 보간, 기어·깜빡이·브레이크는 직전 값을 유지한다. */
class ScenarioPlayer(val scenario: Scenario) {
    private val samples = scenario.samples
    val durationSec: Double = samples.last().t

    private val gears: List<Gear> = run {
        var cur = Gear.P
        samples.map { s -> s.gear?.let { cur = Gear.valueOf(it) }; cur }
    }
    private val signals: List<TurnSignal> = run {
        var cur = TurnSignal.OFF
        samples.map { s -> s.turnSignal?.let { cur = parseSignal(it) }; cur }
    }
    private val odo: DoubleArray = DoubleArray(samples.size).also { d ->
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            d[i] = d[i - 1] + (a.speedKph + b.speedKph) / 2 / 3.6 * (b.t - a.t)
        }
    }

    /** 처음으로 D가 되는 시나리오 시각. 하이브리드는 기어 D 조작을 이 시각에 맞춘다. */
    val driveStartSec: Double = samples.indices.firstOrNull { gears[it] == Gear.D }?.let { samples[it].t } ?: 0.0

    fun stateAt(t: Double): VehicleState {
        val i = samples.indexOfLast { it.t <= t }.coerceAtLeast(0)
        val a = samples[i]
        val b = samples.getOrElse(i + 1) { a }
        val f = if (b.t > a.t) ((t - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0) else 0.0
        fun lerp(x: Double, y: Double) = x + (y - x) * f
        val atEnd = i == samples.lastIndex
        return VehicleState(
            gear = gears[i],
            speedKph = lerp(a.speedKph, b.speedKph),
            steeringDeg = lerp(a.steeringDeg ?: 0.0, b.steeringDeg ?: a.steeringDeg ?: 0.0),
            turnSignal = signals[i],
            brakeOn = (a.brakePct ?: 0.0) > 0.0 || (atEnd && (b.brakePct ?: 0.0) > 0.0),
            lat = lerp(a.lat, b.lat),
            lng = lerp(a.lng, b.lng),
        )
    }

    /** 안내 지점을 경로에서 가장 가까운 샘플의 누적거리로 바꾼다. 거리순 정렬. */
    fun markers(): List<RouteMarker> = scenario.naviPoints.mapNotNull { p ->
        val type = runCatching { MarkerType.valueOf(p.type) }.getOrNull() ?: return@mapNotNull null
        val k = cos(Math.toRadians(p.lat))
        val i = samples.indices.minByOrNull { abs(samples[it].lat - p.lat) + abs(samples[it].lng - p.lng) * k }!!
        RouteMarker(p.id, type, odo[i], p.speedLimitKph)
    }.sortedBy { it.odoM }

    private fun parseSignal(v: String) = when (v) {
        "LEFT" -> TurnSignal.LEFT
        "RIGHT" -> TurnSignal.RIGHT
        else -> TurnSignal.OFF // HAZARD 포함. 실제 SDK에는 HAZARD 값이 없다.
    }
}
