package com.drivescore.scoring

enum class Badge { NO_PENALTY, SIGNAL_PERFECT, TURN_PERFECT, PERSONAL_BEST }

/** F-09 배지. 짧은 이동으로 쉽게 받지 않도록 최소 거리를 둔다. */
object BadgeRules {
    private const val MIN_DISTANCE_M = 500.0
    private val SIGNAL_REASONS = setOf(PenaltyReason.SIGNAL_MISSING, PenaltyReason.SIGNAL_LATE, PenaltyReason.SIGNAL_NOT_CANCELLED)

    fun award(total: Int, items: List<ItemScore>, penalties: List<Penalty>, distanceM: Double, previousTotals: List<Int>): List<Badge> {
        if (distanceM < MIN_DISTANCE_M) return emptyList()
        fun applicable(c: Category) = items.any { it.category == c && !it.notApplicable }
        return buildList {
            if (penalties.isEmpty()) add(Badge.NO_PENALTY)
            if ((applicable(Category.TURN) || applicable(Category.LANE_CHANGE)) && penalties.none { it.reason in SIGNAL_REASONS }) add(Badge.SIGNAL_PERFECT)
            if (applicable(Category.TURN) && penalties.none { it.category == Category.TURN }) add(Badge.TURN_PERFECT)
            if (previousTotals.isNotEmpty() && total > previousTotals.max()) add(Badge.PERSONAL_BEST)
        }
    }
}
