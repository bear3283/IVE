package com.drivescore.scoring

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class CourseSignature(
    val destLat: Double,
    val destLng: Double,
    val turns: List<MarkerType>,
)

/** 목적지 + 회전 순서 유사도로 같은 코스를 찾는다(F-01). */
class CourseMatcher(private val config: ScoringConfig = ScoringConfig()) {

    /** @return 가장 비슷한 코스의 키. 기준을 넘는 코스가 없으면 null */
    fun <K> findMatch(candidate: CourseSignature, courses: Map<K, CourseSignature>): K? =
        courses.entries
            .filter { distanceM(candidate, it.value) <= config.courseDestinationM }
            .map { it.key to similarity(candidate.turns, it.value.turns) }
            .filter { it.second >= config.courseMinSimilarity }
            .maxByOrNull { it.second }
            ?.first

    fun similarity(a: List<MarkerType>, b: List<MarkerType>): Double {
        val longest = max(a.size, b.size)
        return if (longest == 0) 1.0 else 1.0 - editDistance(a, b).toDouble() / longest
    }

    private fun editDistance(a: List<MarkerType>, b: List<MarkerType>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.size + 1)
            row[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                row[j + 1] = min(min(row[j] + 1, prev[j + 1] + 1), prev[j] + cost)
            }
            prev = row
        }
        return prev[b.size]
    }

    private fun distanceM(a: CourseSignature, b: CourseSignature): Double {
        val dLat = Math.toRadians(b.destLat - a.destLat)
        val dLng = Math.toRadians(b.destLng - a.destLng)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.destLat)) * cos(Math.toRadians(b.destLat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
