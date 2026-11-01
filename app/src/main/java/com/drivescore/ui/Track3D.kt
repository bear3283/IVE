package com.drivescore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.drivescore.domain.ReplayGeometry
import com.drivescore.domain.TripDetails
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MAX_RIBBON_POINTS = 400
private const val PITCH = 0.8f
private const val CAMERA_DISTANCE = 3.4f
private const val GRID_EXTENT = 1.5f
private const val GRID_STEP = 0.3f
private const val PIN_HEIGHT = 0.3f
private const val RIBBON_HEIGHT = 0.02f
private const val CAR_LENGTH = 0.13f
private const val CAR_WIDTH = 0.08f
private const val CAR_HEIGHT = 0.05f

/** 원근 투영. 3D 라이브러리 없이 Canvas로 그린다. */
private class Camera(private val cx: Float, private val cy: Float, private val focal: Float, yaw: Float) {
    private val cy0 = cos(yaw)
    private val sy0 = sin(yaw)
    private val cp = cos(PITCH)
    private val sp = sin(PITCH)
    fun at(x: Float, y: Float, z: Float = 0f): Offset {
        val rx = x * cy0 - y * sy0
        val ry = x * sy0 + y * cy0
        val up = ry * cp + z * sp
        val depth = ry * sp - z * cp
        val s = focal / (CAMERA_DISTANCE + depth)
        return Offset(cx + rx * s, cy - up * s)
    }
}

/**
 * F-08 주행선. 기울인 바닥 위에 주행선을 세우고 카메라를 천천히 돌린다.
 * 지나온 구간은 밝게, 남은 구간은 어둡게 그리고 감점 지점에는 기둥을 세운다.
 * @param t 주행 시각 @param play 재생 시각. 배속과 무관하게 카메라가 일정하게 돌도록 쓴다.
 */
@Composable
fun Track3D(details: TripDetails, times: DoubleArray, t: Double, play: Double, modifier: Modifier) {
    val track = details.track
    val pts = remember(track) {
        val raw = ReplayGeometry.project(track)
        val minX = raw.minOfOrNull { it.x } ?: 0.0
        val maxX = raw.maxOfOrNull { it.x } ?: 1.0
        val minY = raw.minOfOrNull { it.y } ?: 0.0
        val maxY = raw.maxOfOrNull { it.y } ?: 1.0
        val half = max(max(maxX - minX, maxY - minY) / 2, 1.0)
        FloatArray(raw.size * 2).also { out ->
            raw.forEachIndexed { i, p ->
                out[i * 2] = ((p.x - (minX + maxX) / 2) / half).toFloat()
                out[i * 2 + 1] = ((p.y - (minY + maxY) / 2) / half).toFloat()
            }
        }
    }
    val stride = max(1, pts.size / 2 / MAX_RIBBON_POINTS)
    val pins = remember(details.trip.id) { details.penalties.map { it.t to ReplayGeometry.indexAt(times, it.t) } }

    Canvas(modifier.clipToBounds()) {
        val n = pts.size / 2
        if (n < 2) return@Canvas
        val yaw = (sin(play * 0.12) * 0.55).toFloat() + 0.25f
        val cam = Camera(size.width / 2, size.height * 0.52f, min(size.width, size.height) * 1.6f, yaw)
        fun p(i: Int, z: Float = 0f) = cam.at(pts[i * 2], pts[i * 2 + 1], z)

        // 바닥 격자.
        var g = -GRID_EXTENT
        while (g <= GRID_EXTENT + 0.001f) {
            drawLine(Palette.Line, cam.at(g, -GRID_EXTENT), cam.at(g, GRID_EXTENT), 1.5f)
            drawLine(Palette.Line, cam.at(-GRID_EXTENT, g), cam.at(GRID_EXTENT, g), 1.5f)
            g += GRID_STEP
        }

        val current = ReplayGeometry.indexAt(times, t).coerceIn(0, n - 1)
        fun ribbon(from: Int, to: Int, z: Float) = Path().apply {
            var i = from
            p(i, z).let { moveTo(it.x, it.y) }
            while (i < to) {
                i = min(i + stride, to)
                p(i, z).let { lineTo(it.x, it.y) }
            }
        }
        val round = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        drawPath(ribbon(0, n - 1, 0f), Color.Black.copy(alpha = 0.45f), style = round(14f))
        drawPath(ribbon(0, n - 1, RIBBON_HEIGHT), Palette.Good.copy(alpha = 0.38f), style = round(9f))
        if (current > 0) drawPath(ribbon(0, current, RIBBON_HEIGHT), Palette.Good, style = round(11f))
        // 감점 구간은 지나온 뒤에만 빨갛게 칠한다.
        for ((pt, _) in pins) {
            val from = ReplayGeometry.indexAt(times, pt - 3.0).coerceIn(0, n - 1)
            val to = min(ReplayGeometry.indexAt(times, pt + 3.0).coerceIn(0, n - 1), current)
            if (to > from) drawPath(ribbon(from, to, RIBBON_HEIGHT), Palette.Bad, style = round(11f))
        }
        for ((pt, index) in pins) {
            val i = index.coerceIn(0, n - 1)
            val active = abs(pt - t) <= 3.0
            val pulse = if (active) 1f + 0.35f * ((sin(play * 7.0).toFloat() + 1f) / 2f) else 1f
            val top = p(i, PIN_HEIGHT)
            drawLine(if (pt <= t) Palette.Bad else Palette.Bad.copy(alpha = 0.4f), p(i, RIBBON_HEIGHT), top, 5f)
            drawCircle(Palette.Bad.copy(alpha = if (pt <= t || active) 1f else 0.4f), 13f * pulse, top)
        }

        // 차: 진행 방향으로 회전하는 쐐기. 그림자를 바닥에 떨어뜨린다.
        val a = max(current - 3, 0)
        val b = min(current + 3, n - 1)
        var dx = pts[b * 2] - pts[a * 2]
        var dy = pts[b * 2 + 1] - pts[a * 2 + 1]
        val len = max(kotlin.math.sqrt(dx * dx + dy * dy), 1e-4f)
        dx /= len
        dy /= len
        val x = pts[current * 2]
        val y = pts[current * 2 + 1]
        fun corner(forward: Float, side: Float, z: Float) = cam.at(x + dx * forward - dy * side, y + dy * forward + dx * side, z)
        fun wedge(z: Float) = Path().apply {
            corner(CAR_LENGTH, 0f, z).let { moveTo(it.x, it.y) }
            corner(-CAR_LENGTH, CAR_WIDTH, z).let { lineTo(it.x, it.y) }
            corner(-CAR_LENGTH * 0.6f, 0f, z).let { lineTo(it.x, it.y) }
            corner(-CAR_LENGTH, -CAR_WIDTH, z).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(wedge(0f), Color.Black.copy(alpha = 0.6f))
        drawPath(wedge(CAR_HEIGHT), Color.White)
    }
}
