package com.drivescore.scoring

data class GradeProgress(
    val current: Grade,
    val next: Grade?,
    val requiredAverage: Int?,
    val requiredTrips: Int?,
    /** 다음 등급 판정에 들어가는 최근 주행 수. */
    val countedTrips: Int,
    val currentAverage: Double?,
)

/** 등급 계산. 연습 기록과 제외 기록은 호출자가 빼고 넘긴다. 한 번 오른 등급은 내려가지 않는다. */
class GradeEngine(private val rulesFor: (Grade) -> GradeRules = GradeRules.Companion::forGrade) {

    /** @param scores 오래된 순서의 총점 목록 */
    fun evaluate(scores: List<Int>): GradeProgress {
        var current = Grade.BEGINNER
        for (n in 1..scores.size) {
            val prefix = scores.subList(0, n)
            Grade.entries.filter { it > current && meets(prefix, it) }.maxOrNull()?.let { current = it }
        }
        val next = Grade.entries.firstOrNull { it > current }
            ?: return GradeProgress(current, null, null, null, 0, null)
        val rules = rulesFor(next)
        val recent = scores.takeLast(rules.entryTrips)
        return GradeProgress(
            current = current,
            next = next,
            requiredAverage = rules.entryAverage,
            requiredTrips = rules.entryTrips,
            countedTrips = recent.size,
            currentAverage = recent.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    private fun meets(scores: List<Int>, grade: Grade): Boolean {
        val rules = rulesFor(grade)
        if (scores.size < rules.entryTrips) return false
        return scores.takeLast(rules.entryTrips).average() >= rules.entryAverage
    }
}
