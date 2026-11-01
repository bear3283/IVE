package com.drivescore.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivescore.R
import com.drivescore.domain.TripDetails
import com.drivescore.scoring.ScoringConfig
import kotlin.math.roundToInt

private const val MAX_PENALTY_LINES = 3

/** F-09. 왼쪽은 점수와 동작, 오른쪽은 이유. 스크롤 없이 한 화면에 담는다. */
@Composable
fun ResultScreen(
    details: TripDetails,
    listening: Boolean,
    caption: String?,
    onMic: () -> Unit,
    onReplay: () -> Unit,
    onClose: () -> Unit,
) {
    val count = remember(details.trip.id) { Animatable(0f) }
    LaunchedEffect(details.trip.id) { count.animateTo(details.trip.total.toFloat(), tween(1500)) }

    Row(Modifier.fillMaxSize().background(Palette.Background).padding(48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${count.value.toInt()}", color = Palette.Text, fontSize = 240.sp, lineHeight = 240.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.points_unit), color = Palette.Muted, fontSize = 56.sp, modifier = Modifier.padding(start = 12.dp, bottom = 36.dp))
            }
            val previous = details.previousTotal
            val diff = previous?.let { details.trip.total - it }
            Text(
                when {
                    diff == null -> stringResource(R.string.compare_first)
                    diff > 0 -> stringResource(R.string.compare_up, diff)
                    diff < 0 -> stringResource(R.string.compare_down, -diff)
                    else -> stringResource(R.string.compare_same)
                },
                color = when { diff == null || diff == 0 -> Palette.Text; diff > 0 -> Palette.Good; else -> Palette.Bad },
                fontSize = 40.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.grade_line, stringResource(gradeLabel(details.trip.grade))), color = Palette.Muted, fontSize = 30.sp)
                details.badges.forEach { b ->
                    Text(
                        stringResource(b.label()), color = Palette.Signal, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Palette.Panel, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (details.trip.lowSample) Text(stringResource(R.string.low_sample), color = Palette.Muted, fontSize = 30.sp)
            Spacer(Modifier.weight(1f))
            VoiceBar(listening, caption, stringResource(R.string.mic_hint_result), onMic, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(TouchGap)) {
                CarButton(stringResource(R.string.action_replay), onReplay, Modifier.weight(1f), CarButtonStyle.PRIMARY)
                CarButton(stringResource(R.string.action_close), onClose, Modifier.weight(1f))
            }
        }
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 평가한 항목이 적으면 레이더 모양이 되지 않으니 목록으로 보여 준다.
            if (details.items.count { !it.notApplicable } >= 3) {
                RadarChart(details.items, details.previousItems, Modifier.fillMaxWidth().weight(1f))
            } else {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                    details.items.filterNot { it.notApplicable }.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(item.category.label()), color = Palette.Text, fontSize = 34.sp, modifier = Modifier.weight(1f))
                            Text("★".repeat(item.stars) + "☆".repeat((5 - item.stars).coerceAtLeast(0)), color = Palette.Signal, fontSize = 36.sp)
                        }
                    }
                }
            }
            if (details.penalties.isEmpty()) Text(stringResource(R.string.no_penalty), color = Palette.Good, fontSize = 36.sp)
            val factor = remember(details.trip.id) { ScoringConfig().distanceFactor(details.trip.distanceM / 1000) }
            details.penalties.sortedByDescending { it.deduction }.take(MAX_PENALTY_LINES).forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.describe(), color = Palette.Text, fontSize = 34.sp, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.deduction_points, (p.deduction * factor).roundToInt()), color = Palette.Bad, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
            }
            val more = details.penalties.size - MAX_PENALTY_LINES
            if (more > 0) Text(stringResource(R.string.penalties_more, more), color = Palette.Muted, fontSize = 30.sp)
        }
    }
}
