package com.drivescore.scoring

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ReplayConfig(
    /** 감점 전후로 1배속 재생할 시간. */
    val windowSec: Double = 5.0,
    val minFastSpeed: Double = 8.0,
    val maxFastSpeed: Double = 16.0,
    val targetPlaySec: Double = 45.0,
    val maxPlaySec: Double = 60.0,
)

data class ReplaySegment(val startT: Double, val endT: Double, val speed: Double) {
    val playSec get() = (endT - startT) / speed
}

/** F-08 재생 시간표. 감점 구간은 1배속, 나머지는 빠르게 돌려 전체를 정해진 길이에 맞춘다. */
class ReplayTimeline(val segments: List<ReplaySegment>) {
    val durationSec: Double = segments.lastOrNull()?.endT ?: 0.0
    val playDurationSec: Double = segments.sumOf { it.playSec }

    fun tripTimeAt(playSec: Double): Double {
        var left = playSec.coerceAtLeast(0.0)
        for (s in segments) {
            if (left <= s.playSec) return s.startT + left * s.speed
            left -= s.playSec
        }
        return durationSec
    }

    fun playTimeOf(tripT: Double): Double {
        var played = 0.0
        for (s in segments) {
            if (tripT <= s.endT) return played + (tripT - s.startT).coerceAtLeast(0.0) / s.speed
            played += s.playSec
        }
        return playDurationSec
    }

    fun speedAt(tripT: Double): Double =
        (segments.firstOrNull { tripT <= it.endT } ?: segments.lastOrNull())?.speed ?: 1.0

    companion object {
        fun build(durationSec: Double, penalties: List<Penalty>, config: ReplayConfig = ReplayConfig()): ReplayTimeline {
            if (durationSec <= 0) return ReplayTimeline(emptyList())

            // 감점이 많아 길이를 넘으면 심각도가 높은 것만 1배속으로 남긴다.
            var windows = emptyList<ClosedFloatingPointRange<Double>>()
            penalties.sortedWith(compareByDescending<Penalty> { it.deduction }.thenBy { it.t }).forEach { p ->
                val candidate = merge(windows + ((p.t - config.windowSec).coerceAtLeast(0.0)..min(p.t + config.windowSec, durationSec)))
                if (candidate.sumOf { it.endInclusive - it.start } <= config.targetPlaySec) windows = candidate
            }
            val slowSec = windows.sumOf { it.endInclusive - it.start }
            val fastSec = durationSec - slowSec

            var speed = (fastSec / max(config.targetPlaySec - slowSec, 1.0)).coerceIn(config.minFastSpeed, config.maxFastSpeed)
            // 긴 주행은 16배속으로도 넘친다. 배속보다 재생 길이를 지킨다.
            if (slowSec + fastSec / speed > config.maxPlaySec) speed = fastSec / (config.maxPlaySec - slowSec)

            val out = mutableListOf<ReplaySegment>()
            var cursor = 0.0
            for (w in windows) {
                if (w.start > cursor) out += ReplaySegment(cursor, w.start, speed)
                if (w.endInclusive > w.start) out += ReplaySegment(w.start, w.endInclusive, 1.0)
                cursor = w.endInclusive
            }
            if (cursor < durationSec) out += ReplaySegment(cursor, durationSec, speed)
            return ReplayTimeline(out)
        }

        private fun merge(ranges: List<ClosedFloatingPointRange<Double>>): List<ClosedFloatingPointRange<Double>> {
            val out = mutableListOf<ClosedFloatingPointRange<Double>>()
            for (r in ranges.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && r.start <= last.endInclusive) out[out.lastIndex] = last.start..max(last.endInclusive, r.endInclusive)
                else out += r
            }
            return out
        }
    }
}

/** 재생 시점에 설명할 감점. 창 안에서 가장 가까운 것. */
fun activePenalty(penalties: List<Penalty>, tripT: Double, windowSec: Double = ReplayConfig().windowSec): Penalty? =
    penalties.filter { abs(it.t - tripT) <= windowSec }.minByOrNull { abs(it.t - tripT) }
