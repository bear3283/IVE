package com.drivescore.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.drivescore.R
import com.drivescore.scoring.Category
import com.drivescore.scoring.ItemScore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val RINGS = 4

/** 항목별 평가 레이더. 축은 평가한 항목만, 별점은 축 이름 아래에 둔다. 점선은 지난번 주행. */
@Composable
fun RadarChart(items: List<ItemScore>, previous: List<ItemScore>, modifier: Modifier = Modifier) {
    val axes = items.filterNot { it.notApplicable }
    val names = axes.map { stringResource(it.category.label()) }
    val before = remember(previous) { previous.associateBy(ItemScore::category) }
    val grow = remember(items) { Animatable(0f) }
    LaunchedEffect(items) { grow.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()

    Canvas(modifier) {
        val n = axes.size
        if (n < 3) return@Canvas
        val c = center
        val r = min(size.width, size.height) / 2 * 0.68f
        fun at(i: Int, ratio: Float): Offset {
            val a = -PI / 2 + 2 * PI * i / n
            return Offset(c.x + r * ratio * cos(a).toFloat(), c.y + r * ratio * sin(a).toFloat())
        }
        fun polygon(ratio: (Int) -> Float) = Path().apply {
            for (i in 0 until n) at(i, ratio(i)).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
        for (ring in 1..RINGS) drawPath(polygon { ring / RINGS.toFloat() }, Palette.Line, style = Stroke(2f))
        for (i in 0 until n) drawLine(Palette.Line, c, at(i, 1f), 2f)

        fun ratio(item: ItemScore) = if (item.maxPoints > 0) (item.points / item.maxPoints).toFloat().coerceIn(0f, 1f) else 0f
        // 0점 항목도 모양이 보이도록 중심에서 조금 띄운다.
        fun shown(v: Float) = 0.06f + 0.94f * v

        val prev = axes.map { before[it.category] }
        if (prev.all { it != null && !it.notApplicable }) {
            drawPath(
                polygon { shown(ratio(prev[it]!!)) }, Palette.Muted,
                style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        }
        val now = polygon { shown(ratio(axes[it])) * grow.value }
        drawPath(now, Palette.Good.copy(alpha = 0.28f))
        drawPath(now, Palette.Good, style = Stroke(5f))
        for (i in 0 until n) {
            val v = ratio(axes[i])
            drawCircle(if (v < 0.5f) Palette.Bad else Palette.Good, 9f, at(i, shown(v) * grow.value))
        }

        for (i in 0 until n) {
            val stars = "★".repeat(axes[i].stars) + "☆".repeat(max(5 - axes[i].stars, 0))
            val title = measurer.measure(names[i], TextStyle(color = Palette.Text, fontSize = 24.sp))
            val rating = measurer.measure(stars, TextStyle(color = Palette.Signal, fontSize = 20.sp))
            val w = max(title.size.width, rating.size.width)
            val h = title.size.height + rating.size.height
            val p = at(i, 1.14f)
            val cosA = cos(-PI / 2 + 2 * PI * i / n).toFloat()
            val sinA = sin(-PI / 2 + 2 * PI * i / n).toFloat()
            val x = (p.x - w / 2f + cosA * w / 2f).coerceIn(0f, size.width - w)
            val y = (p.y - h / 2f + sinA * h / 2f).coerceIn(0f, size.height - h)
            drawText(title, topLeft = Offset(x + (w - title.size.width) / 2f, y))
            drawText(rating, topLeft = Offset(x + (w - rating.size.width) / 2f, y + title.size.height))
        }
    }
}
