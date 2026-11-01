package com.drivescore.scoring

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 순수 함수 채점기. 같은 입력이면 항상 같은 결과를 낸다. */
class TripScorer(private val config: ScoringConfig = ScoringConfig()) {

    fun score(
        samples: List<SignalSample>,
        markers: List<RouteMarker> = emptyList(),
        capabilities: SignalCapabilities = SignalCapabilities(),
        rules: GradeRules,
    ): TripResult = Run(samples, markers, capabilities, rules).result()

    private inner class Run(
        val samples: List<SignalSample>,
        markers: List<RouteMarker>,
        val caps: SignalCapabilities,
        val rules: GradeRules,
    ) {
        val c = config
        val endOdo = samples.lastOrNull()?.odoM ?: 0.0
        val passed = markers.filter { it.odoM <= endOdo + c.naviMatchM }
        val maneuvers =
            if (caps.steering) ManeuverClassifier(c).classify(samples, passed, rules) else emptyList()
        val penalties = mutableListOf<Penalty>()

        fun add(cat: Category, reason: PenaltyReason, s: SignalSample, sev: Double, obs: Map<String, Double> = emptyMap()) {
            penalties += Penalty(cat, reason, s.t, s.odoM, sev, obs, s.lat, s.lng)
        }

        fun sampleAt(t: Double): SignalSample =
            samples.firstOrNull { it.t >= t - EPS } ?: samples.last()

        fun sampleAtOdo(odo: Double): SignalSample =
            samples.firstOrNull { it.odoM >= odo } ?: samples.last()

        fun result(): TripResult {
            if (samples.isEmpty()) return summarize(0.0)
            val hardBrakes = findHardBrakes()
            if (caps.steering) {
                maneuvers.forEach { m ->
                    when (m.type) {
                        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.U_TURN -> scoreTurn(m)
                        ManeuverType.LANE_CHANGE -> scoreLaneChange(m, hardBrakes)
                        ManeuverType.WOBBLE -> add(
                            Category.STRAIGHT, PenaltyReason.STEERING_WOBBLE, sampleAt(m.startT), c.severityWobble,
                            mapOf("peakDeg" to abs(m.peakDeg), "toleranceDeg" to rules.straightToleranceDeg),
                        )
                        else -> Unit
                    }
                }
            }
            hardBrakes.forEach {
                add(Category.SMOOTH, PenaltyReason.HARD_BRAKE, it.sample, c.severityHardEvent, mapOf("mps2" to it.mps2))
            }
            findHardAccels().forEach {
                add(Category.SMOOTH, PenaltyReason.HARD_ACCEL, it.sample, c.severityHardEvent, mapOf("mps2" to it.mps2))
            }
            scoreCameras()
            scoreDecelPrep()
            scoreTooSlow()
            penalties.sortBy { it.t }
            return summarize((endOdo - samples.first().odoM) / 1000.0)
        }

        fun scoreTurn(m: Maneuver) {
            val start = sampleAt(m.startT)
            checkSignal(m, Category.TURN)
            if (start.speedKph > c.turnEntryMaxKph) {
                add(Category.TURN, PenaltyReason.INSUFFICIENT_SLOWDOWN, start, c.severitySlowdown, mapOf("speedKph" to start.speedKph))
            }
            val rate = maxSteeringRate(m)
            if (rate > c.abruptSteeringDegPerSec) {
                add(Category.TURN, PenaltyReason.ABRUPT_STEERING, start, c.severityAbrupt, mapOf("degPerSec" to rate))
            }
            if (rules.requireCancelAfterTurn) checkCancelled(m, Category.TURN, c.turnCancelSec)
        }

        fun scoreLaneChange(m: Maneuver, hardBrakes: List<HardEvent>) {
            checkSignal(m, Category.LANE_CHANGE)
            checkCancelled(m, Category.LANE_CHANGE, c.laneCancelSec)
            hardBrakes.firstOrNull { it.sample.t >= m.endT && it.sample.t <= m.endT + c.gapBrakeWindowSec }?.let {
                add(
                    Category.LANE_CHANGE, PenaltyReason.HARD_BRAKE_AFTER_LANE_CHANGE, it.sample, c.severityGap,
                    mapOf("mps2" to it.mps2, "secAfter" to it.sample.t - m.endT),
                )
            }
        }

        fun directionSignal(m: Maneuver) = if (m.direction > 0) TurnSignal.RIGHT else TurnSignal.LEFT

        fun checkSignal(m: Maneuver, cat: Category) {
            if (!caps.turnSignal) return
            val want = directionSignal(m)
            // 구간 시작(centerDeg 초과)이 아니라 조향이 실제로 시작된 시점부터 잰다.
            var startIdx = samples.indexOfFirst { it.t >= m.startT - EPS }
            while (startIdx > 0 && abs(samples[startIdx - 1].steeringDeg ?: 0.0) > c.steeringOnsetDeg) startIdx--
            val start = samples[startIdx]
            val leadSec: Double? = if (start.turnSignal == want) {
                var i = startIdx
                while (i > 0 && samples[i - 1].turnSignal == want) i--
                start.t - samples[i].t
            } else if (samples.any { it.t > m.startT && it.t <= m.endT && it.turnSignal == want }) {
                0.0
            } else {
                null
            }
            when {
                leadSec == null -> add(cat, PenaltyReason.SIGNAL_MISSING, start, c.severityMissing)
                leadSec + EPS < rules.signalLeadSec -> add(
                    cat, PenaltyReason.SIGNAL_LATE, start, c.severityLate,
                    mapOf("leadSec" to leadSec, "requiredSec" to rules.signalLeadSec),
                )
            }
        }

        fun checkCancelled(m: Maneuver, cat: Category, withinSec: Double) {
            if (!caps.turnSignal) return
            val want = directionSignal(m)
            if (samples.last().t < m.endT + withinSec) return
            val after = sampleAt(m.endT + withinSec)
            if (after.turnSignal == want) {
                add(cat, PenaltyReason.SIGNAL_NOT_CANCELLED, after, c.severityNotCancelled)
            }
        }

        fun maxSteeringRate(m: Maneuver): Double {
            val seg = samples.filter { it.t >= m.startT - EPS && it.t <= m.endT + EPS }
            return seg.zipWithNext { a, b ->
                val dt = b.t - a.t
                if (dt <= 0) 0.0 else abs((b.steeringDeg ?: 0.0) - (a.steeringDeg ?: 0.0)) / dt
            }.maxOrNull() ?: 0.0
        }

        /** 창 안의 가속도가 조건을 넘는 연속 구간마다 가장 강한 지점 하나. */
        fun findAccelEvents(over: (Double) -> Boolean): List<HardEvent> {
            val out = mutableListOf<HardEvent>()
            var current: HardEvent? = null
            var back = 0
            samples.forEachIndexed { i, s ->
                while (back < i && s.t - samples[back + 1].t >= c.accelWindowSec - EPS) back++
                val dt = s.t - samples[back].t
                val a = if (dt >= c.accelWindowSec - EPS) (s.speedKph - samples[back].speedKph) / 3.6 / dt else 0.0
                if (over(a)) {
                    if (current == null || abs(a) > current!!.mps2) current = HardEvent(s, abs(a))
                } else {
                    current?.let { out += it }
                    current = null
                }
            }
            current?.let { out += it }
            return out
        }

        fun findHardBrakes(): List<HardEvent> =
            findAccelEvents { it < -rules.hardBrakeMps2 }.filter { e ->
                if (!caps.brake) return@filter true
                val window = samples.filter { it.t >= e.sample.t - c.pedalLookbackSec && it.t <= e.sample.t }
                // 값을 못 받았으면(null) 감속도만으로 판정한다.
                window.any { it.brakeOn != false }
            }

        fun findHardAccels(): List<HardEvent> = findAccelEvents { it > rules.hardAccelMps2 }

        fun scoreCameras() {
            passed.filter { it.type == MarkerType.SPEED_CAMERA && it.speedLimitKph != null }.forEach { m ->
                val s = sampleAtOdo(m.odoM)
                if (s.speedKph > m.speedLimitKph!!) {
                    add(Category.CAMERA, PenaltyReason.OVER_SPEED, s, c.severityOverSpeed, mapOf("speedKph" to s.speedKph, "limitKph" to m.speedLimitKph.toDouble()))
                }
            }
            sections().forEach { (start, end) ->
                val limit = start.speedLimitKph ?: return@forEach
                val from = sampleAtOdo(start.odoM)
                val to = sampleAtOdo(end?.odoM ?: endOdo)
                val sec = to.t - from.t
                if (sec <= 0) return@forEach
                val avg = (to.odoM - from.odoM) / sec * 3.6
                if (avg > limit + SECTION_AVG_EPS) {
                    add(Category.CAMERA, PenaltyReason.OVER_SPEED, to, c.severityOverSpeed, mapOf("avgKph" to avg, "limitKph" to limit.toDouble()))
                }
            }
        }

        fun sections(): List<Pair<RouteMarker, RouteMarker?>> {
            val ends = passed.filter { it.type == MarkerType.SECTION_END }.sortedBy { it.odoM }
            return passed.filter { it.type == MarkerType.SECTION_START }.sortedBy { it.odoM }.map { s ->
                s to ends.firstOrNull { it.odoM > s.odoM }
            }
        }

        fun prepPoints() = passed.filter {
            it.type == MarkerType.TURN_LEFT || it.type == MarkerType.TURN_RIGHT || it.type == MarkerType.U_TURN ||
                it.type == MarkerType.SPEED_CAMERA || it.type == MarkerType.SECTION_START
        }

        fun scoreDecelPrep() {
            prepPoints().forEach { m ->
                val checkOdo = m.odoM - c.decelPrepLeadM
                if (checkOdo < samples.first().odoM) return@forEach
                val s = sampleAtOdo(checkOdo)
                val target = m.speedLimitKph?.toDouble() ?: c.decelTurnTargetKph
                if (s.speedKph > target + c.decelMarginKph) {
                    add(Category.DECEL_PREP, PenaltyReason.LATE_DECEL, s, c.severityLateDecel, mapOf("speedKph" to s.speedKph, "targetKph" to target))
                }
            }
        }

        /** 움직이면서 브레이크도 안 밟은 저속이 오래 이어지면 악용으로 본다. 정체는 브레이크 조작으로 구분한다. */
        fun scoreTooSlow() {
            var start: SignalSample? = null
            var reported = false
            for (s in samples) {
                val slow = s.speedKph > 1.0 && s.speedKph < c.tooSlowKph && s.brakeOn != true
                if (!slow) {
                    start = null
                    reported = false
                    continue
                }
                val from = start ?: s.also { start = it }
                if (!reported && s.t - from.t >= c.tooSlowMinSec) {
                    add(Category.DECEL_PREP, PenaltyReason.TOO_SLOW, from, c.severityTooSlow, mapOf("speedKph" to s.speedKph))
                    reported = true
                }
            }
        }

        fun summarize(km: Double): TripResult {
            val distanceOpp = max(1.0, km / c.kmPerOpportunity)
            fun count(vararg t: ManeuverType) = maneuvers.count { it.type in t }.toDouble()
            val opportunities = mapOf(
                Category.TURN to count(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.U_TURN),
                Category.LANE_CHANGE to count(ManeuverType.LANE_CHANGE),
                Category.SMOOTH to distanceOpp,
                Category.CAMERA to (passed.count { it.type == MarkerType.SPEED_CAMERA } + sections().size).toDouble(),
                Category.STRAIGHT to distanceOpp,
                Category.DECEL_PREP to prepPoints().size.toDouble(),
            )
            val base = mapOf(
                Category.TURN to c.pointsTurn,
                Category.LANE_CHANGE to c.pointsLaneChange,
                Category.SMOOTH to c.pointsSmooth,
                Category.CAMERA to c.pointsCamera,
                Category.STRAIGHT to c.pointsStraight,
                Category.DECEL_PREP to c.pointsDecelPrep,
            ).filterKeys { caps.steering || it !in STEERING_CATEGORIES }
            val scale = 100.0 / base.values.sum()

            val turns = passed.count { it.type == MarkerType.TURN_LEFT || it.type == MarkerType.TURN_RIGHT || it.type == MarkerType.U_TURN }
            val cameras = passed.count { it.type == MarkerType.SPEED_CAMERA || it.type == MarkerType.SECTION_START }
            val difficulty = 1.0 + min(c.difficultyMaxBonus, c.difficultyPerTurn * turns + c.difficultyPerCamera * cameras)

            var loss = 0.0
            val items = base.map { (cat, points) ->
                val maxPoints = points * scale
                val mine = penalties.filter { it.category == cat }
                val opp = opportunities.getValue(cat)
                val rate = min(1.0, mine.sumOf { it.severity } / max(opp, 1.0))
                loss += maxPoints * rate
                val got = maxPoints * (1 - rate)
                ItemScore(cat, maxPoints, got, (got / maxPoints * 5).roundToInt(), opp == 0.0 && mine.isEmpty())
            }
            val total = (100 - loss / difficulty).roundToInt().coerceIn(0, 100)
            return TripResult(total, difficulty, items, penalties.toList(), maneuvers)
        }
    }

    private data class HardEvent(val sample: SignalSample, val mps2: Double)

    private companion object {
        const val EPS = 1e-6
        /** 샘플 이산화로 평균 속도가 제한을 근소하게 넘는 경우를 무시한다. */
        const val SECTION_AVG_EPS = 0.5
        val STEERING_CATEGORIES = setOf(Category.TURN, Category.LANE_CHANGE, Category.STRAIGHT)
    }
}
