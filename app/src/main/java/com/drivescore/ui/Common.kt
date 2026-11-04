package com.drivescore.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.drivescore.R
import com.drivescore.scoring.*

object Palette {
    val Background = Color(0xFF0B0E11)
    val Panel = Color(0xFF161B20)
    val Text = Color(0xFFF2F4F6)
    val Muted = Color(0xFF9AA4AE)
    val Good = Color(0xFF3DDC97)
    val Bad = Color(0xFFFF5A5F)
    val Signal = Color(0xFFFFB020)
    val Line = Color(0xFF2B333B)
}

@Composable
fun RecordingCover() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.recording_notice), color = Palette.Muted, fontSize = 34.sp)
    }
}

@StringRes
fun Category.label() = when (this) {
    Category.SIGNAL -> R.string.category_signal
    Category.SMOOTH -> R.string.category_smooth
    Category.CAMERA -> R.string.category_camera
    Category.STEERING -> R.string.category_straight
    Category.DECEL_PREP -> R.string.category_decel
}

@StringRes
fun Badge.label() = when (this) {
    Badge.NO_PENALTY -> R.string.badge_no_penalty
    Badge.SIGNAL_PERFECT -> R.string.badge_signal
    Badge.GRADE_CHALLENGE -> R.string.badge_challenge
    Badge.PERSONAL_BEST -> R.string.badge_best
}

@StringRes
fun gradeLabel(name: String) = when (runCatching { Grade.valueOf(name) }.getOrDefault(Grade.BEGINNER)) {
    Grade.BEGINNER -> R.string.grade_beginner
    Grade.SKILLED -> R.string.grade_skilled
    Grade.VETERAN -> R.string.grade_veteran
    Grade.MASTER -> R.string.grade_master
}

@Composable
fun Penalty.describe(): String {
    val mps2 = observed["mps2"]
    val peak = observed["peakDeg"]
    val lead = observed["leadM"]
    return when (reason) {
        PenaltyReason.SIGNAL_MISSING -> stringResource(R.string.reason_signal_missing)
        PenaltyReason.SIGNAL_LATE -> lead?.let { stringResource(R.string.reason_signal_late_at, it) } ?: stringResource(R.string.reason_signal_late)
        PenaltyReason.SIGNAL_NOT_CANCELLED -> stringResource(R.string.reason_not_cancelled)
        PenaltyReason.INSUFFICIENT_SLOWDOWN -> stringResource(R.string.reason_slowdown)
        PenaltyReason.ABRUPT_STEERING -> stringResource(R.string.reason_abrupt)
        PenaltyReason.HARD_BRAKE_AFTER_LANE_CHANGE -> stringResource(R.string.reason_brake_after_lane)
        PenaltyReason.HARD_BRAKE -> mps2?.let { stringResource(R.string.reason_hard_brake_at, it) } ?: stringResource(R.string.reason_hard_brake)
        PenaltyReason.HARD_ACCEL -> stringResource(R.string.reason_hard_accel)
        PenaltyReason.OVER_SPEED -> stringResource(R.string.reason_over_speed)
        PenaltyReason.STEERING_WOBBLE -> peak?.let { stringResource(R.string.reason_wobble_at, it) } ?: stringResource(R.string.reason_wobble)
        PenaltyReason.LATE_DECEL -> stringResource(R.string.reason_late_decel)
        PenaltyReason.TOO_SLOW -> stringResource(R.string.reason_too_slow)
    }
}
