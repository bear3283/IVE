package com.drivescore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

private const val PENALTY_COLOR_SEC = 3.0
private const val CHART_BEFORE_SEC = 10.0
private const val CHART_AFTER_SEC = 2.0
private const val CHART_MAX_KPH = 120f

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

    Column(Modifier.fillMaxSize().background(Palette.Background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TrackCanvas(details, times, t, Modifier.weight(1.5f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(20.dp)))
            Overlay(track, times, t, point, Modifier.weight(1f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(20.dp)).padding(20.dp))
        }
        Text(
            active?.describe() ?: stringResource(R.string.replay_normal),
            color = if (active != null) Palette.Bad else Palette.Muted,
            fontSize = 34.sp, fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val seek = { target: Double? -> if (target != null) play = timeline.playTimeOf((target - PENALTY_COLOR_SEC).coerceAtLeast(0.0)) }
            OutlinedButton({ seek(details.penalties.lastOrNull { it.t < t - PENALTY_COLOR_SEC - 0.5 }?.t ?: 0.0) }, Modifier.heightIn(min = 80.dp)) {
                Text(stringResource(R.string.action_prev), fontSize = 26.sp)
            }
            Button({ playing = !playing }, Modifier.heightIn(min = 80.dp).widthIn(min = 200.dp)) {
                Text(stringResource(if (playing) R.string.action_pause else R.string.action_play), fontSize = 26.sp)
            }
            OutlinedButton({ seek(details.penalties.firstOrNull { it.t - PENALTY_COLOR_SEC > t + 0.5 }?.t) }, Modifier.heightIn(min = 80.dp)) {
                Text(stringResource(R.string.action_next), fontSize = 26.sp)
            }
            Text(stringResource(R.string.replay_speed, timeline.speedAt(t)), color = Palette.Muted, fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.weight(1f))
            MicButton(listening, onMic)
            OutlinedButton(onFinished, Modifier.heightIn(min = 80.dp)) { Text(stringResource(R.string.action_result), fontSize = 26.sp) }
        }
    }
}

@Composable
private fun TrackCanvas(details: TripDetails, times: DoubleArray, t: Double, modifier: Modifier) {
    val track = details.track
    val pts = remember(track) { ReplayGeometry.project(track) }
    val current by rememberUpdatedState(t)
    Box(modifier.padding(24.dp).drawWithCache {
        // 궤적은 크기가 바뀔 때만 계산한다.
        val minX = pts.minOfOrNull { it.x } ?: 0.0
        val maxX = pts.maxOfOrNull { it.x } ?: 1.0
        val minY = pts.minOfOrNull { it.y } ?: 0.0
        val maxY = pts.maxOfOrNull { it.y } ?: 1.0
        val scale = min(size.width / max(maxX - minX, 1.0), size.height / max(maxY - minY, 1.0)).toFloat()
        val ox = (size.width - (maxX - minX).toFloat() * scale) / 2
        val oy = (size.height - (maxY - minY).toFloat() * scale) / 2
        fun at(i: Int) = Offset(ox + (pts[i].x - minX).toFloat() * scale, size.height - oy - (pts[i].y - minY).toFloat() * scale)
        val bad = BooleanArray(pts.size) { i -> details.penalties.any { abs(it.t - track[i].t) <= PENALTY_COLOR_SEC } }
        fun pathOf(wanted: Boolean) = Path().apply {
            var open = false
            for (i in 1 until pts.size) {
                if (bad[i] == wanted) {
                    if (!open) at(i - 1).let { moveTo(it.x, it.y) }
                    at(i).let { lineTo(it.x, it.y) }
                    open = true
                } else open = false
            }
        }
        val normal = pathOf(false)
        val penalty = pathOf(true)
        val pins = details.penalties.map { it.t to at(ReplayGeometry.indexAt(times, it.t).coerceAtMost(pts.lastIndex)) }
        val stroke = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            if (pts.isEmpty()) return@onDrawBehind
            drawPath(normal, Palette.Good, style = stroke)
            drawPath(penalty, Palette.Bad, style = stroke)
            pins.forEach { (pt, o) ->
                drawCircle(Palette.Bad, if (abs(pt - current) <= PENALTY_COLOR_SEC) 22f else 13f, o)
                drawCircle(Palette.Panel, 5f, o)
            }
            val i = ReplayGeometry.indexAt(times, current).coerceAtMost(pts.lastIndex)
            val car = at(i)
            val ahead = at(min(i + 1, pts.lastIndex))
            val behind = at(max(i - 1, 0))
            val angle = Math.toDegrees(atan2((ahead.x - behind.x).toDouble(), (behind.y - ahead.y).toDouble())).toFloat()
            rotate(angle, car) {
                drawPath(Path().apply {
                    moveTo(car.x, car.y - 30f); lineTo(car.x + 19f, car.y + 20f); lineTo(car.x, car.y + 10f); lineTo(car.x - 19f, car.y + 20f); close()
                }, Color.White)
            }
        }
    }.fillMaxSize())
}

@Composable
private fun Overlay(track: List<TrackPoint>, times: DoubleArray, t: Double, point: TrackPoint?, modifier: Modifier) {
    val i = ReplayGeometry.indexAt(times, t)
    val decel = run {
        val a = track.getOrNull(i) ?: return@run 0.0
        val b = track.getOrNull(i + 1) ?: return@run 0.0
        ((a.speedKph - b.speedKph) / 3.6 / max(b.t - a.t, 0.05)).coerceAtLeast(0.0)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SteeringWheel(point?.steeringDeg ?: 0.0, Modifier.size(120.dp))
            Column {
                Label(R.string.overlay_steering)
                Value(stringResource(R.string.value_deg, point?.steeringDeg ?: 0.0))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column {
                Label(R.string.overlay_speed)
                Value("${(point?.speedKph ?: 0.0).toInt()} ${stringResource(R.string.unit_kph)}")
            }
            Column {
                Label(R.string.overlay_decel)
                Value("%.1f ${stringResource(R.string.unit_mps2)}".format(decel), if (decel >= 3.5) Palette.Bad else Palette.Text)
            }
        }
        Chart(track, times, t, Modifier.fillMaxWidth().weight(1f)) { p, x, h ->
            Offset(x, h - (p.speedKph.toFloat() / CHART_MAX_KPH).coerceIn(0f, 1f) * h)
        }
        Label(R.string.overlay_signal)
        Band(track, times, t, Palette.Signal) { it.turnSignal == "LEFT" || it.turnSignal == "RIGHT" }
        Label(R.string.overlay_brake)
        Band(track, times, t, Palette.Bad) { it.brakeOn == true }
    }
}

@Composable private fun Label(id: Int) = Text(stringResource(id), color = Palette.Muted, fontSize = 22.sp)

@Composable
private fun Value(text: String, color: Color = Palette.Text) = Text(text, color = color, fontSize = 32.sp, fontWeight = FontWeight.Bold)

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

/** 현재 시점 앞뒤 구간의 선 그래프. */
@Composable
private fun Chart(track: List<TrackPoint>, times: DoubleArray, t: Double, modifier: Modifier, toPoint: (TrackPoint, Float, Float) -> Offset) = Canvas(modifier) {
    val span = (CHART_BEFORE_SEC + CHART_AFTER_SEC).toFloat()
    val path = Path()
    var started = false
    for (i in ReplayGeometry.indexAt(times, t - CHART_BEFORE_SEC)..ReplayGeometry.indexAt(times, t + CHART_AFTER_SEC)) {
        val p = track.getOrNull(i) ?: continue
        val o = toPoint(p, ((p.t - t + CHART_BEFORE_SEC).toFloat() / span).coerceIn(0f, 1f) * size.width, size.height)
        if (started) path.lineTo(o.x, o.y) else path.moveTo(o.x, o.y)
        started = true
    }
    drawPath(path, Palette.Good, style = Stroke(5f))
    val now = (CHART_BEFORE_SEC.toFloat() / span) * size.width
    drawLine(Palette.Muted, Offset(now, 0f), Offset(now, size.height), 2f)
}

/** 켜짐/꺼짐 띠. 공개 API의 브레이크가 ON/OFF뿐이라 값 대신 구간으로 보여준다(D1). */
@Composable
private fun Band(track: List<TrackPoint>, times: DoubleArray, t: Double, color: Color, on: (TrackPoint) -> Boolean) =
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
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
