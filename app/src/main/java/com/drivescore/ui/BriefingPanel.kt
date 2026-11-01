package com.drivescore.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivescore.R
import com.drivescore.domain.Briefing
import com.drivescore.scoring.MarkerType

/** F-02. 좌표가 없는 내비 안내(거리만 제공)에 맞춰 경로를 한 줄로 그린다. */
@Composable
fun BriefingPanel(briefing: Briefing?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(36.dp)) {
        if (briefing == null) {
            Text(stringResource(R.string.brief_no_route), color = Palette.Muted, fontSize = 44.sp, lineHeight = 60.sp)
            return@Column
        }
        Text(stringResource(R.string.brief_destination, briefing.destination), color = Palette.Text, fontSize = 72.sp, fontWeight = FontWeight.Bold)
        RouteStrip(briefing, Modifier.fillMaxWidth().height(90.dp))
        // 개수가 곧 범례다. 색과 뜻을 따로 외우지 않아도 된다.
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Legend(Palette.Good, stringResource(R.string.brief_turns, briefing.turns))
            Legend(Palette.Signal, stringResource(R.string.brief_cameras, briefing.cameras))
        }
        Spacer(Modifier.weight(1f))
        if (briefing.lastTotal != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Stat(R.string.brief_last, briefing.lastTotal)
                briefing.bestTotal?.let { Stat(R.string.brief_best, it) }
            }
            briefing.lastPenalty?.let { Legend(Palette.Bad, stringResource(R.string.brief_last_penalty, it.describe())) }
        } else {
            Text(stringResource(R.string.brief_new_course), color = Palette.Muted, fontSize = 34.sp)
        }
    }
}

/** 선이 그려지며 점이 경로를 따라가고, 지나간 지점이 차례로 켜진다. 정차 화면에서만 보인다. */
@Composable
private fun RouteStrip(b: Briefing, modifier: Modifier) {
    val progress = remember(b.destination) { Animatable(0f) }
    LaunchedEffect(b.destination) { progress.animateTo(1f, tween(2600, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val end = maxOf(b.markers.maxOfOrNull { it.odoM } ?: 0.0, b.lastPenalty?.odoM ?: 0.0, 1.0)
        val y = size.height / 2
        val pad = 24f
        fun x(odo: Double) = pad + (odo / end).toFloat() * (size.width - 2 * pad)
        val head = pad + progress.value * (size.width - 2 * pad)
        drawLine(Palette.Line, Offset(pad, y), Offset(size.width - pad, y), 12f, StrokeCap.Round)
        drawLine(Palette.Muted, Offset(pad, y), Offset(head, y), 12f, StrokeCap.Round)
        fun pin(odo: Double, color: Color) {
            val at = x(odo)
            // 점이 지나갈 때 살짝 커졌다가 자리를 잡는다.
            val pop = if (progress.value >= 1f) 1f else ((head - at) / 60f).coerceIn(0f, 1f)
            if (pop > 0f) drawCircle(color, 18f * (0.6f + 0.4f * pop) + 8f * (1f - pop), Offset(at, y))
        }
        b.markers.forEach { m ->
            pin(
                m.odoM,
                when (m.type) {
                    MarkerType.TURN_LEFT, MarkerType.TURN_RIGHT, MarkerType.U_TURN -> Palette.Good
                    MarkerType.SPEED_CAMERA, MarkerType.SECTION_START, MarkerType.SECTION_END -> Palette.Signal
                    MarkerType.DESTINATION -> Palette.Text
                },
            )
        }
        b.lastPenalty?.let { pin(it.odoM, Palette.Bad) }
        if (progress.value < 1f) drawCircle(Color.White, 12f, Offset(head, y))
    }
}

@Composable
private fun Legend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(24.dp).background(color, CircleShape))
    Text(label, color = Palette.Text, fontSize = 34.sp)
}

@Composable
private fun Stat(label: Int, value: Int) = Column {
    Text(stringResource(label), color = Palette.Muted, fontSize = 30.sp)
    Text("$value" + stringResource(R.string.points_unit), color = Palette.Text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
}

/** 정차 중 마이크 버튼과 마지막 안내 문장. 마이크는 항상 왼쪽 아래, 운전석 가까이에 둔다. */
@Composable
fun VoiceBar(listening: Boolean, caption: String?, hint: String, onMic: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TouchGap)) {
        MicButton(listening, onMic)
        Text(caption ?: hint, color = if (caption != null) Palette.Text else Palette.Muted, fontSize = 30.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MicButton(listening: Boolean, onMic: () -> Unit) = CarButton(
    stringResource(if (listening) R.string.action_listening else R.string.action_mic),
    onMic,
    style = if (listening) CarButtonStyle.ACTIVE else CarButtonStyle.SECONDARY,
)
