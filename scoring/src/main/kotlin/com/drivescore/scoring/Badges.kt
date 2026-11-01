package com.drivescore.scoring

enum class Badge { NO_PENALTY, SIGNAL_PERFECT, GRADE_CHALLENGE, PERSONAL_BEST }

/** F-09 배지. 짧은 이동이나 표본이 적은 주행으로는 받지 않는다. */
object BadgeRules {
    private const val MIN_DISTANCE_M = 500.0
    private val SIGNAL_REASONS = setOf(PenaltyReason.SIGNAL_MISSING, PenaltyReason.SIGNAL_LATE, PenaltyReason.SIGNAL_NOT_CANCELLED)

    /** 등급 과제: 평가한 동작 모두에서 등급의 시간만큼 깜빡이를 미리 켜고 법 기준도 지켰다. */
    fun challengePassed(rules: GradeRules, result: TripResult): Boolean =
        result.signalLeadsSec.isNotEmpty() &&
            result.signalLeadsSec.all { it >= rules.signalLeadSec } &&
            result.penalties.none { it.reason in SIGNAL_REASONS }

    fun award(
        total: Int,
        items: List<ItemScore>,
        penalties: List<Penalty>,
        distanceM: Double,
        previousTotals: List<Int>,
        challengePassed: Boolean = false,
        lowSample: Boolean = false,
    ): List<Badge> {
        if (distanceM < MIN_DISTANCE_M || lowSample) return emptyList()
        val signalEvaluated = items.any { it.category == Category.SIGNAL && !it.notApplicable }
        return buildList {
            if (penalties.isEmpty()) add(Badge.NO_PENALTY)
            if (signalEvaluated && penalties.none { it.reason in SIGNAL_REASONS }) add(Badge.SIGNAL_PERFECT)
            if (challengePassed) add(Badge.GRADE_CHALLENGE)
            if (previousTotals.isNotEmpty() && total > previousTotals.max()) add(Badge.PERSONAL_BEST)
        }
    }
}
