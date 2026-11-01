package com.drivescore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivescore.R
import com.drivescore.data.db.TrackPoint
import com.drivescore.domain.ReplayGeometry
import com.drivescore.domain.TripDetails
import com.drivescore.scoring.ReplayTimeline
import com.drivescore.scoring.activePenalty

private const val PENALTY_COLOR_SEC = 3.0
private const val CHART_BEFORE_SEC = 10.0
private const val CHART_AFTER_SEC = 2.0

/** F-08. 호출자가 주행 중에는 이 화면을 구성에서 빼므로 재생은 그 즉시 멈춘다. */
@Composable
fun ReplayScreen(
    details: TripDetails,
    listening: Boolean,
    nextRequests: kotlinx.coroutines.flow.Flow<Unit>,
    onMic: () -> Unit,
    onActivePenalty: (com.drivescore.scoring.Penalty?) -> Unit,
    onFinished: () -> Unit,
) {
    val timeline = remember(details.trip.id) { ReplayTimeline.build(details.trip.durationSec, details.penalties) }
    val track = details.track
    val times = remember(track) { DoubleArray(track.size) { track[it].t } }
    var play by remember(details.trip.id) { mutableDoubleStateOf(0.0) }
    var playing by remember(details.trip.id) { mutableStateOf(true) }

    LaunchedEffect(playing, timeline) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            play += (now - last) / 1e9
            last = now
            if (play >= timeline.playDurationSec) {
                onFinished()
                return@LaunchedEffect
            }
        }
    }

    val t = timeline.tripTimeAt(play)
    val point = track.getOrNull(ReplayGeometry.indexAt(times, t))
    val active = activePenalty(details.penalties, t)
    LaunchedEffect(active) { onActivePenalty(active) }
    val currentTime by rememberUpdatedState(t)
    LaunchedEffect(nextRequests) {
        nextRequests.collect {
            details.penalties.firstOrNull { it.t - PENALTY_COLOR_SEC > currentTime + 0.5 }?.let {
                play = timeline.playTimeOf((it.t - PENALTY_COLOR_SEC).coerceAtLeast(0.0))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Track3D(details, times, t, play, Modifier.weight(2.4f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(24.dp)))
            Overlay(track, times, t, point, Modifier.weight(1f).fillMaxHeight())
        }
        // 감점이 없는 동안에도 자리를 비워 두어 화면이 출렁이지 않게 한다.
        Text(active?.describe() ?: "", color = Palette.Bad, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.heightIn(min = 68.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TouchGap)) {
            val seek = { target: Double? -> if (target != null) play = timeline.playTimeOf((target - PENALTY_COLOR_SEC).coerceAtLeast(0.0)) }
            CarButton(stringResource(R.string.action_prev), { seek(details.penalties.lastOrNull { it.t < t - PENALTY_COLOR_SEC - 0.5 }?.t ?: 0.0) })
            CarButton(stringResource(if (playing) R.string.action_pause else R.string.action_play), { playing = !playing }, Modifier.widthIn(min = 260.dp), CarButtonStyle.PRIMARY)
            CarButton(stringResource(R.string.action_next), { seek(details.penalties.firstOrNull { it.t - PENALTY_COLOR_SEC > t + 0.5 }?.t) })
            Spacer(Modifier.weight(1f))
            MicButton(listening, onMic)
            Spacer(Modifier.width(TouchGap))
            CarButton(stringResource(R.string.action_result), onFinished)
        }
    }
}

/** 이 앱만 보여 줄 수 있는 차량 신호만 남긴다: 핸들, 속도, 깜빡이, 브레이크. */
@Composable
private fun Overlay(track: List<TrackPoint>, times: DoubleArray, t: Double, point: TrackPoint?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SteeringWheel(point?.steeringDeg ?: 0.0, Modifier.size(260.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(point?.speedKph ?: 0.0).toInt()}", color = Palette.Text, fontSize = 96.sp, lineHeight = 100.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.unit_kph), color = Palette.Muted, fontSize = 30.sp, modifier = Modifier.padding(start = 10.dp, bottom = 16.dp))
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.overlay_signal), color = Palette.Muted, fontSize = 28.sp)
            Band(track, times, t, Palette.Signal) { it.turnSignal == "LEFT" || it.turnSignal == "RIGHT" }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.overlay_brake), color = Palette.Muted, fontSize = 28.sp)
            Band(track, times, t, Palette.Bad) { it.brakeOn == true }
        }
    }
}

@Composable
private fun SteeringWheel(deg: Double, modifier: Modifier) = Canvas(modifier) {
    val r = size.minDimension / 2 - 6f
    val c = center
    drawCircle(Palette.Muted, r, c, style = Stroke(10f))
    rotate(deg.toFloat(), c) {
        drawLine(Palette.Muted, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 8f)
        drawLine(Palette.Muted, c, Offset(c.x, c.y + r), 8f)
        drawCircle(Palette.Signal, 9f, Offset(c.x, c.y - r))
    }
}

/** 켜짐/꺼짐 띠. 공개 API의 브레이크가 ON/OFF뿐이라 값 대신 구간으로 보여준다(D1). */
@Composable
private fun Band(track: List<TrackPoint>, times: DoubleArray, t: Double, color: Color, on: (TrackPoint) -> Boolean) =
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val span = (CHART_BEFORE_SEC + CHART_AFTER_SEC).toFloat()
        drawRect(Palette.Line)
        val from = ReplayGeometry.indexAt(times, t - CHART_BEFORE_SEC)
        val to = ReplayGeometry.indexAt(times, t + CHART_AFTER_SEC)
        for (i in from until to) {
            val a = track.getOrNull(i) ?: continue
            val b = track.getOrNull(i + 1) ?: continue
            if (!on(a)) continue
            val x0 = ((a.t - t + CHART_BEFORE_SEC).toFloat() / span).coerceIn(0f, 1f) * size.width
            val x1 = ((b.t - t + CHART_BEFORE_SEC).toFloat() / span).coerceIn(0f, 1f) * size.width
            drawRect(color, Offset(x0, 0f), Size(x1 - x0, size.height))
        }
        val now = (CHART_BEFORE_SEC.toFloat() / span) * size.width
        drawLine(Color.White, Offset(now, 0f), Offset(now, size.height), 3f)
    }
