package com.drivescore.scoring

enum class TurnSignal { OFF, LEFT, RIGHT }
enum class Gear { P, R, N, D }

/** 10Hz로 정규화된 차량 신호. null은 값을 받지 못했다는 뜻이다. */
data class SignalSample(
    val t: Double,
    val odoM: Double,
    val speedKph: Double,
    val steeringDeg: Double? = 0.0,
    val turnSignal: TurnSignal? = TurnSignal.OFF,
    val brakeOn: Boolean? = false,
    val gear: Gear = Gear.D,
    val lat: Double? = null,
    val lng: Double? = null,
)

enum class MarkerType { TURN_LEFT, TURN_RIGHT, U_TURN, SPEED_CAMERA, SECTION_START, SECTION_END, DESTINATION }

/** 내비 안내 지점. 실제 SDK는 좌표 없이 남은 거리만 주므로 주행 누적거리로 표현한다. */
data class RouteMarker(
    val id: String,
    val type: MarkerType,
    val odoM: Double,
    val speedLimitKph: Int? = null,
)

enum class ManeuverType { STRAIGHT, CURVE, LANE_CHANGE, TURN_LEFT, TURN_RIGHT, U_TURN, WOBBLE }

data class Maneuver(
    val type: ManeuverType,
    val startT: Double,
    val endT: Double,
    val startOdoM: Double,
    val endOdoM: Double,
    val peakDeg: Double = 0.0,
    /** 첫 조향 방향. 오른쪽 양수. */
    val direction: Int = 0,
)

enum class Category { TURN, LANE_CHANGE, SMOOTH, CAMERA, STRAIGHT, DECEL_PREP }

enum class PenaltyReason {
    SIGNAL_MISSING,
    SIGNAL_LATE,
    SIGNAL_NOT_CANCELLED,
    INSUFFICIENT_SLOWDOWN,
    ABRUPT_STEERING,
    HARD_BRAKE_AFTER_LANE_CHANGE,
    HARD_BRAKE,
    HARD_ACCEL,
    OVER_SPEED,
    STEERING_WOBBLE,
    LATE_DECEL,
    TOO_SLOW,
}

data class Penalty(
    val category: Category,
    val reason: PenaltyReason,
    val t: Double,
    val odoM: Double,
    val severity: Double,
    val observed: Map<String, Double> = emptyMap(),
    val lat: Double? = null,
    val lng: Double? = null,
)

/** 차종이 지원하는 신호. Vehicle SDK의 checkXXXCapabilities 결과. */
data class SignalCapabilities(
    val steering: Boolean = true,
    val turnSignal: Boolean = true,
    val brake: Boolean = true,
)

data class ItemScore(
    val category: Category,
    val maxPoints: Double,
    val points: Double,
    val stars: Int,
    val notApplicable: Boolean,
)

data class TripResult(
    val total: Int,
    val difficulty: Double,
    val items: List<ItemScore>,
    val penalties: List<Penalty>,
    val maneuvers: List<Maneuver>,
)
