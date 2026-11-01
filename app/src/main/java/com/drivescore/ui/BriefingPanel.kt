package com.drivescore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (briefing == null) {
            Text(stringResource(R.string.brief_no_route), color = Palette.Muted, fontSize = 30.sp)
            return@Column
        }
        Text(stringResource(R.string.brief_destination, briefing.destination), color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(stringResource(R.string.brief_counts, briefing.turns, briefing.cameras), color = Palette.Text, fontSize = 28.sp)
            Text(stringResource(R.string.brief_difficulty) + " " + "★".repeat(briefing.difficultyStars), color = Palette.Signal, fontSize = 28.sp)
        }
        RouteStrip(briefing, Modifier.fillMaxWidth().height(70.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Legend(Palette.Good, R.string.brief_legend_turn)
            Legend(Palette.Signal, R.string.brief_legend_camera)
            Legend(Palette.Text, R.string.brief_legend_dest)
            if (briefing.lastPenalty != null) Legend(Palette.Bad, R.string.brief_legend_penalty)
        }
        if (briefing.lastTotal != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Stat(R.string.brief_last, briefing.lastTotal)
                briefing.bestTotal?.let { Stat(R.string.brief_best, it) }
            }
            briefing.lastPenalty?.let { Text(stringResource(R.string.brief_last_penalty, it.describe()), color = Palette.Bad, fontSize = 26.sp) }
        } else {
            Text(stringResource(R.string.brief_new_course), color = Palette.Muted, fontSize = 28.sp)
        }
    }
}

@Composable
private fun RouteStrip(b: Briefing, modifier: Modifier) = Canvas(modifier) {
    val end = maxOf(b.markers.maxOfOrNull { it.odoM } ?: 0.0, b.lastPenalty?.odoM ?: 0.0, 1.0)
    val y = size.height / 2
    val pad = 24f
    fun x(odo: Double) = pad + (odo / end).toFloat() * (size.width - 2 * pad)
    drawLine(Palette.Line, Offset(pad, y), Offset(size.width - pad, y), 12f, StrokeCap.Round)
    b.markers.forEach { m ->
        val c = when (m.type) {
            MarkerType.TURN_LEFT, MarkerType.TURN_RIGHT, MarkerType.U_TURN -> Palette.Good
            MarkerType.SPEED_CAMERA, MarkerType.SECTION_START, MarkerType.SECTION_END -> Palette.Signal
            MarkerType.DESTINATION -> Palette.Text
        }
        drawCircle(c, 18f, Offset(x(m.odoM), y))
    }
    b.lastPenalty?.let { drawCircle(Palette.Bad, 18f, Offset(x(it.odoM), y)) }
}

@Composable
private fun Legend(color: Color, label: Int) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(20.dp).background(color, CircleShape))
    Text(stringResource(label), color = Palette.Muted, fontSize = 24.sp)
}

@Composable
private fun Stat(label: Int, value: Int) = Column {
    Text(stringResource(label), color = Palette.Muted, fontSize = 24.sp)
    Text("$value" + stringResource(R.string.points_unit), color = Palette.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
}

/** 정차 중 마이크 버튼과 마지막 안내 문장. */
@Composable
fun VoiceBar(listening: Boolean, caption: String?, hint: String, onMic: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Palette.Panel, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        MicButton(listening, onMic)
        Text(caption ?: hint, color = if (caption != null) Palette.Text else Palette.Muted, fontSize = 26.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MicButton(listening: Boolean, onMic: () -> Unit) {
    Button(
        onMic, Modifier.heightIn(min = 80.dp).widthIn(min = 180.dp),
        colors = if (listening) ButtonDefaults.buttonColors(containerColor = Palette.Bad) else ButtonDefaults.buttonColors(),
    ) { Text(stringResource(if (listening) R.string.action_listening else R.string.action_mic), fontSize = 26.sp) }
}
