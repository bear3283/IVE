package com.drivescore.domain

import androidx.annotation.StringRes
import com.drivescore.R
import com.drivescore.scoring.*

/** 지금 코스의 출발 전 정보(F-02). */
data class Briefing(
    val destination: String,
    val markers: List<RouteMarker>,
    val turns: Int,
    val cameras: Int,
    val difficultyStars: Int,
    val knownCourse: Boolean,
    val lastTotal: Int?,
    val bestTotal: Int?,
    /** 지난 주행에서 가장 큰 감점 하나. */
    val lastPenalty: Penalty?,
)

/** 음성 문구. 숫자·단위는 읽기 좋은 말로 바꾼다. 화면 문구는 ui.describe를 쓴다. */
class VoiceLines(private val text: (Int, Array<out Any>) -> String) {
    private fun s(@StringRes id: Int, vararg args: Any) = text(id, args)

    fun briefing(b: Briefing): String {
        val p = b.lastPenalty
        return if (b.knownCourse && b.lastTotal != null) buildString {
            append(s(R.string.voice_brief_known, b.destination, b.lastTotal, b.bestTotal ?: b.lastTotal))
            if (p != null) append(' ').append(s(R.string.voice_brief_mission, s(mission(p.category))))
        } else s(R.string.voice_brief_new, b.turns, b.cameras)
    }

    /** F-10. 2~3문장. */
    fun debrief(total: Int, previous: Int?, penaltyCount: Int): String = buildString {
        val diff = previous?.let { total - it }
        append(
            when {
                diff == null -> s(R.string.voice_debrief_first, total)
                diff > 0 -> s(R.string.voice_debrief_up, total, diff)
                diff < 0 -> s(R.string.voice_debrief_down, total, -diff)
                else -> s(R.string.voice_debrief_same, total)
            },
        )
        append(' ')
        append(if (penaltyCount == 0) s(R.string.voice_debrief_clean) else s(R.string.voice_debrief_ask, penaltyCount))
    }

    fun reason(p: Penalty): String {
        val lead = p.observed["leadSec"]
        val peak = p.observed["peakDeg"]
        return when (p.reason) {
            PenaltyReason.SIGNAL_MISSING -> s(R.string.reason_signal_missing)
            PenaltyReason.SIGNAL_LATE -> lead?.let { s(R.string.reason_signal_late_at, it) } ?: s(R.string.reason_signal_late)
            PenaltyReason.SIGNAL_NOT_CANCELLED -> s(R.string.reason_not_cancelled)
            PenaltyReason.INSUFFICIENT_SLOWDOWN -> s(R.string.reason_slowdown)
            PenaltyReason.ABRUPT_STEERING -> s(R.string.reason_abrupt)
            PenaltyReason.HARD_BRAKE_AFTER_LANE_CHANGE -> s(R.string.reason_brake_after_lane)
            PenaltyReason.HARD_BRAKE -> s(R.string.reason_hard_brake)
            PenaltyReason.HARD_ACCEL -> s(R.string.reason_hard_accel)
            PenaltyReason.OVER_SPEED -> s(R.string.reason_over_speed)
            PenaltyReason.STEERING_WOBBLE -> peak?.let { s(R.string.voice_wobble_at, it) } ?: s(R.string.reason_wobble)
            PenaltyReason.LATE_DECEL -> s(R.string.reason_late_decel)
            PenaltyReason.TOO_SLOW -> s(R.string.reason_too_slow)
        }
    }

    /** "왜 감점됐어?" 큰 감점부터 서로 다른 이유 최대 세 개. */
    fun why(penalties: List<Penalty>): String =
        if (penalties.isEmpty()) s(R.string.no_penalty)
        else penalties.sortedByDescending { it.severity }.distinctBy { it.reason }.take(MAX_WHY).joinToString(" ") { reason(it) + "." }

    fun compare(total: Int, previous: Int?): String {
        val diff = previous?.let { total - it }
        return when {
            diff == null -> s(R.string.compare_first)
            diff > 0 -> s(R.string.compare_up, diff)
            diff < 0 -> s(R.string.compare_down, -diff)
            else -> s(R.string.compare_same)
        }
    }

    fun grade(current: Grade, p: GradeProgress): String {
        val now = s(R.string.voice_grade_now, s(gradeRes(current)))
        val next = p.next.takeIf { current < Grade.MASTER } ?: return now
        return now + " " + s(R.string.voice_grade_next, s(gradeRes(next)), p.requiredTrips ?: 0, p.requiredAverage ?: 0)
    }

    fun hazard() = s(R.string.voice_hazard)
    fun notHeard() = s(R.string.voice_not_heard)
    fun skipDone() = s(R.string.voice_skip_done)
    fun switchUnavailable() = s(R.string.voice_switch_unavailable)
    fun nextOnlyInReplay() = s(R.string.voice_next_only_replay)

    @StringRes
    private fun mission(c: Category) = when (c) {
        Category.TURN -> R.string.mission_turn
        Category.LANE_CHANGE -> R.string.mission_lane
        Category.SMOOTH -> R.string.mission_smooth
        Category.CAMERA -> R.string.mission_camera
        Category.STRAIGHT -> R.string.mission_straight
        Category.DECEL_PREP -> R.string.mission_decel
    }

    @StringRes
    private fun gradeRes(g: Grade) = when (g) {
        Grade.BEGINNER -> R.string.grade_beginner
        Grade.SKILLED -> R.string.grade_skilled
        Grade.VETERAN -> R.string.grade_veteran
        Grade.MASTER -> R.string.grade_master
    }

    private companion object { const val MAX_WHY = 3 }
}
