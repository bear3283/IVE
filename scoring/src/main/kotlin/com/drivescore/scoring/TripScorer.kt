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
    ): TripResult = Run(samples, markers, capabilities).result()

    private inner class Run(
        val samples: List<SignalSample>,
        markers: List<RouteMarker>,
        val caps: SignalCapabilities,
    ) {
        val c = config
        val endOdo = samples.lastOrNull()?.odoM ?: 0.0
        val passed = markers.filter { it.odoM <= endOdo + c.naviMatchM }
        val maneuvers =
            if (caps.steering) ManeuverClassifier(c).classify(samples, passed) else emptyList()
        val penalties = mutableListOf<Penalty>()
        val signalLeads = mutableListOf<Double>()

        fun add(cat: Category, reason: PenaltyReason, s: SignalSample, points: Double, obs: Map<String, Double> = emptyMap()) {
            penalties += Penalty(cat, reason, s.t, s.odoM, points, obs, s.lat, s.lng)
        }

        fun sampleAt(t: Double): SignalSample =
            samples.firstOrNull { it.t >= t - EPS } ?: samples.last()

        fun sampleAtOdo(odo: Double): SignalSample =
            samples.firstOrNull { it.odoM >= odo } ?: samples.last()

        fun result(): TripResult {
            if (samples.isEmpty()) return summarize(0.0)
            val laneChanges = maneuvers.filter { it.type == ManeuverType.LANE_CHANGE }
            if (caps.steering) {
                maneuvers.forEach { m ->
                    when (m.type) {
                        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.U_TURN -> scoreTurn(m)
                        ManeuverType.LANE_CHANGE -> scoreLaneChange(m)
                        ManeuverType.WOBBLE -> add(
                            Category.STEERING, PenaltyReason.STEERING_WOBBLE, sampleAt(m.startT), c.deductWobble,
                            mapOf("peakDeg" to abs(m.peakDeg)),
                        )
                        else -> Unit
                    }
                }
            }
            // 차선변경 직후 급제동도 사건은 하나다. 이유만 구체적으로 적고 한 번만 차감한다.
            findHardBrakes().forEach { e ->
                val afterLane = laneChanges.any { e.startT >= it.endT && e.startT <= it.endT + c.gapBrakeWindowSec }
                add(
                    Category.SMOOTH,
                    if (afterLane) PenaltyReason.HARD_BRAKE_AFTER_LANE_CHANGE else PenaltyReason.HARD_BRAKE,
                    e.sample,
                    if (e.mps2 >= c.severeBrakeMps2) c.deductSevereBrake else c.deductHardEvent,
                    mapOf("mps2" to e.mps2),
                )
            }
            findHardAccels().forEach {
                add(Category.SMOOTH, PenaltyReason.HARD_ACCEL, it.sample, c.deductHardEvent, mapOf("mps2" to it.mps2))
            }
            scoreCameras()
            scoreDecelPrep()
            scoreTooSlow()
            removeDuplicateDecel()
            penalties.sortBy { it.t }
            return summarize((endOdo - samples.first().odoM) / 1000.0)
        }

        /** 같은 지점에서 더 무거운 과속이나 감속 부족으로 이미 차감했으면 늦은 감속은 빼낸다. */
        fun removeDuplicateDecel() {
            val heavier = penalties.filter { it.reason == PenaltyReason.OVER_SPEED || it.reason == PenaltyReason.INSUFFICIENT_SLOWDOWN }
            penalties.removeAll { p ->
                p.reason == PenaltyReason.LATE_DECEL && heavier.any { it.odoM >= p.odoM && it.odoM <= p.odoM + c.decelPrepLeadM + c.naviMatchM }
            }
        }

        fun scoreTurn(m: Maneuver) {
            val start = sampleAt(m.startT)
            checkSignal(m)
            if (start.speedKph > c.turnEntryMaxKph) {
                add(Category.DECEL_PREP, PenaltyReason.INSUFFICIENT_SLOWDOWN, start, c.deductSlowdown, mapOf("speedKph" to start.speedKph))
            }
            val rate = maxSteeringRate(m)
            if (rate > c.abruptSteeringDegPerSec) {
                add(Category.STEERING, PenaltyReason.ABRUPT_STEERING, start, c.deductAbrupt, mapOf("degPerSec" to rate))
            }
            checkCancelled(m, c.turnCancelSec)
        }

        fun scoreLaneChange(m: Maneuver) {
            checkSignal(m)
            checkCancelled(m, c.laneCancelSec)
        }

        fun directionSignal(m: Maneuver) = if (m.direction > 0) TurnSignal.RIGHT else TurnSignal.LEFT

        fun checkSignal(m: Maneuver) {
            if (!caps.turnSignal) return
            val want = directionSignal(m)
            // 구간 시작이 아니라 조향이 실제로 시작된 지점부터 잰다.
            var startIdx = samples.indexOfFirst { it.t >= m.startT - EPS }
            while (startIdx > 0 && abs(samples[startIdx - 1].steeringDeg ?: 0.0) > c.steeringOnsetDeg) startIdx--
            val start = samples[startIdx]
            var onIdx: Int? = null
            if (start.turnSignal == want) {
                var i = startIdx
                while (i > 0 && samples[i - 1].turnSignal == want) i--
                onIdx = i
            } else if (samples.any { it.t > m.startT && it.t <= m.endT && it.turnSignal == want }) {
                onIdx = startIdx
            }
            if (onIdx == null) {
                add(Category.SIGNAL, PenaltyReason.SIGNAL_MISSING, start, c.deductSignalMissing)
                return
            }
            val leadSec = start.t - samples[onIdx].t
            val leadM = start.odoM - samples[onIdx].odoM
            signalLeads += leadSec
            if (leadM + EPS < c.signalLegalM) {
                add(
                    Category.SIGNAL, PenaltyReason.SIGNAL_LATE, start, c.deductSignalLate,
                    mapOf("leadM" to leadM, "leadSec" to leadSec, "requiredM" to c.signalLegalM),
                )
            }
        }

        fun checkCancelled(m: Maneuver, withinSec: Double) {
            if (!caps.turnSignal) return
            val want = directionSignal(m)
            if (samples.last().t < m.endT + withinSec) return
            val after = sampleAt(m.endT + withinSec)
            if (after.turnSignal == want) {
                add(Category.SIGNAL, PenaltyReason.SIGNAL_NOT_CANCELLED, after, c.deductNotCancelled)
            }
        }

        /** 짧은 창의 변화량으로 잰다. 인접 샘플만 보면 샘플 간격이 흔들릴 때 값이 튄다. */
        fun maxSteeringRate(m: Maneuver): Double {
            val seg = samples.filter { it.t >= m.startT - EPS && it.t <= m.endT + EPS }
            var back = 0
            var maxRate = 0.0
            seg.forEachIndexed { i, s ->
                while (back < i && s.t - seg[back + 1].t >= c.steeringRateWindowSec - EPS) back++
                val dt = s.t - seg[back].t
                if (dt >= c.steeringRateWindowSec - EPS) {
                    maxRate = max(maxRate, abs((s.steeringDeg ?: 0.0) - (seg[back].steeringDeg ?: 0.0)) / dt)
                }
            }
            return maxRate
        }

        /** 창 안의 가속도가 조건을 넘는 구간마다 가장 강한 지점 하나. 기준값 근처에서 잠깐 내려가도 같은 사건으로 본다. */
        fun findAccelEvents(over: (Double) -> Boolean): List<HardEvent> {
            val out = mutableListOf<HardEvent>()
            var current: HardEvent? = null
            var back = 0
            var lastOverT = 0.0
            samples.forEachIndexed { i, s ->
                while (back < i && s.t - samples[back + 1].t >= c.accelWindowSec - EPS) back++
                val dt = s.t - samples[back].t
                val a = if (dt >= c.accelWindowSec - EPS) (s.speedKph - samples[back].speedKph) / 3.6 / dt else 0.0
                if (over(a)) {
                    if (current == null || abs(a) > current!!.mps2) current = HardEvent(s, abs(a), current?.startT ?: s.t)
                    lastOverT = s.t
                } else if (current != null && s.t - lastOverT >= c.hardEventMergeSec) {
                    out += current!!
                    current = null
                }
            }
            current?.let { out += it }
            return out
        }

        fun findHardBrakes(): List<HardEvent> =
            findAccelEvents { it < -c.hardBrakeMps2 }.filter { e ->
                if (!caps.brake) return@filter true
                val window = samples.filter { it.t >= e.sample.t - c.pedalLookbackSec && it.t <= e.sample.t }
                // 값을 못 받았으면(null) 감속도만으로 판정한다.
                window.any { it.brakeOn != false }
            }

        fun findHardAccels(): List<HardEvent> = findAccelEvents { it > c.hardAccelMps2 }

        fun scoreCameras() {
            passed.filter { it.type == MarkerType.SPEED_CAMERA && it.speedLimitKph != null }.forEach { m ->
                val s = sampleAtOdo(m.odoM)
                if (s.speedKph > m.speedLimitKph!!) {
                    add(Category.CAMERA, PenaltyReason.OVER_SPEED, s, c.deductOverSpeed, mapOf("speedKph" to s.speedKph, "limitKph" to m.speedLimitKph.toDouble()))
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
                    add(Category.CAMERA, PenaltyReason.OVER_SPEED, to, c.deductOverSpeed, mapOf("avgKph" to avg, "limitKph" to limit.toDouble()))
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
                    add(Category.DECEL_PREP, PenaltyReason.LATE_DECEL, s, c.deductLateDecel, mapOf("speedKph" to s.speedKph, "targetKph" to target))
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
                    add(Category.DECEL_PREP, PenaltyReason.TOO_SLOW, from, c.deductTooSlow, mapOf("speedKph" to s.speedKph))
                    reported = true
                }
            }
        }

        /** 100점에서 사건별 점수를 뺀다. 항목마다 상한이 있고, 긴 주행은 거리 계수로 줄인다. */
        fun summarize(km: Double): TripResult {
            fun count(vararg t: ManeuverType) = maneuvers.count { it.type in t }
            val signalOpp = count(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.U_TURN, ManeuverType.LANE_CHANGE)
            val cameraOpp = passed.count { it.type == MarkerType.SPEED_CAMERA } + sections().size
            val decelOpp = prepPoints().size
            val opportunities = mapOf(
                Category.SIGNAL to signalOpp,
                Category.CAMERA to cameraOpp,
                Category.SMOOTH to 1,
                Category.STEERING to 1,
                Category.DECEL_PREP to decelOpp,
            )
            val caps2 = buildMap {
                if (caps.steering && caps.turnSignal) put(Category.SIGNAL, c.capSignal)
                put(Category.CAMERA, c.capCamera)
                put(Category.SMOOTH, c.capSmooth)
                if (caps.steering) put(Category.STEERING, c.capSteering)
                put(Category.DECEL_PREP, c.capDecel)
            }
            val factor = c.distanceFactor(km)
            var loss = 0.0
            val items = caps2.map { (cat, cap) ->
                val mine = penalties.filter { it.category == cat }
                val lost = min(cap, mine.sumOf { it.deduction } * factor)
                loss += lost
                val got = cap - lost
                ItemScore(cat, cap, got, (got / cap * 5).roundToInt(), opportunities.getValue(cat) == 0 && mine.isEmpty())
            }
            return TripResult(
                total = (100 - loss).roundToInt().coerceIn(0, 100),
                items = items,
                penalties = penalties.toList(),
                maneuvers = maneuvers,
                signalLeadsSec = signalLeads.toList(),
                lowSample = km < c.lowSampleKm && signalOpp + cameraOpp + decelOpp < c.lowSampleOpportunities,
            )
        }
    }

    /** @param startT 기준을 처음 넘은 시각 @param sample 가장 강한 지점 */
    private data class HardEvent(val sample: SignalSample, val mps2: Double, val startT: Double)

    private companion object {
        const val EPS = 1e-6
        /** 샘플 이산화로 평균 속도가 제한을 근소하게 넘는 경우를 무시한다. */
        const val SECTION_AVG_EPS = 0.5
    }
}
