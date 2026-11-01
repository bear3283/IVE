package com.drivescore.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivescore.R
import com.drivescore.domain.TripDetails

/** F-09. 총점 카운트업, 항목별 별점, 배지, 지난번 대비. */
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

    Row(Modifier.fillMaxSize().background(Palette.Background).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.grade_line, stringResource(gradeLabel(details.trip.grade))), color = Palette.Muted, fontSize = 26.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${count.value.toInt()}", color = Palette.Text, fontSize = 150.sp, lineHeight = 150.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.points_unit), color = Palette.Muted, fontSize = 44.sp, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp))
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
                color = when { diff == null || diff == 0 -> Palette.Muted; diff > 0 -> Palette.Good; else -> Palette.Bad },
                fontSize = 30.sp,
            )
            if (details.badges.isEmpty()) {
                Text(stringResource(R.string.no_badge), color = Palette.Muted, fontSize = 24.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    details.badges.forEach { b ->
                        Text(
                            stringResource(b.label()), color = Palette.Background, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Palette.Signal, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            VoiceBar(listening, caption, stringResource(R.string.mic_hint_result), onMic, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onReplay, Modifier.heightIn(min = 88.dp).weight(1f)) { Text(stringResource(R.string.action_replay), fontSize = 28.sp) }
                OutlinedButton(onClose, Modifier.heightIn(min = 88.dp).weight(1f)) { Text(stringResource(R.string.action_close), fontSize = 28.sp) }
            }
        }
        Column(
            Modifier.weight(1.1f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(20.dp)).padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.items_title), color = Palette.Muted, fontSize = 24.sp)
            // 평가한 항목이 적으면 레이더 모양이 되지 않으니 목록으로 보여 준다.
            if (details.items.count { !it.notApplicable } >= 3) {
                RadarChart(details.items, details.previousItems, Modifier.fillMaxWidth().height(430.dp))
            } else {
                details.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(item.category.label()), color = Palette.Text, fontSize = 30.sp, modifier = Modifier.weight(1f))
                        if (item.notApplicable) {
                            Text(stringResource(R.string.not_applicable), color = Palette.Muted, fontSize = 26.sp)
                        } else {
                            Text("★".repeat(item.stars) + "☆".repeat((5 - item.stars).coerceAtLeast(0)), color = Palette.Signal, fontSize = 34.sp)
                        }
                    }
                }
            }
            Divider(color = Palette.Line, modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.penalties_title), color = Palette.Muted, fontSize = 24.sp)
            if (details.penalties.isEmpty()) Text(stringResource(R.string.no_penalty), color = Palette.Good, fontSize = 28.sp)
            details.penalties.forEach { Text(it.describe(), color = Palette.Text, fontSize = 28.sp) }
        }
    }
}
