package com.drivescore.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 터치 타깃 높이와 버튼 사이 간격. 차가 흔들려도 옆 버튼을 누르지 않도록 넉넉히 둔다. */
val TouchHeight = 104.dp
val TouchGap = 32.dp
private val ButtonShape = RoundedCornerShape(20.dp)

/**
 * 오조작 방지. 연속 터치 한 번만 받고, 화면이 막 바뀐 직후의 터치는 버린다.
 * 예: 리플레이가 끝나 결과 화면으로 넘어가는 순간 누른 일시정지가 같은 자리의 다른 버튼을 누르는 경우.
 */
class TapGuard(
    private val repeatMs: Long = 400,
    private val settleMs: Long = 800,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var blockedUntil = Long.MIN_VALUE

    /** 화면이 바뀔 때 부른다. */
    fun screenChanged() {
        blockedUntil = now() + settleMs
    }

    fun allow(): Boolean {
        val t = now()
        if (t < blockedUntil) return false
        blockedUntil = t + repeatMs
        return true
    }
}

val tapGuard = TapGuard()

fun guarded(onClick: () -> Unit): () -> Unit = { if (tapGuard.allow()) onClick() }

enum class CarButtonStyle { PRIMARY, SECONDARY, ACTIVE }

/** 앱의 모든 버튼. 화면마다 주 동작은 하나만 PRIMARY로 둔다. */
@Composable
fun CarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CarButtonStyle = CarButtonStyle.SECONDARY,
) = CarButtonFrame(onClick, modifier, style) {
    Text(label, fontSize = 30.sp, fontWeight = if (style == CarButtonStyle.SECONDARY) FontWeight.Medium else FontWeight.Bold)
}

@Composable
fun CarButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CarButtonStyle = CarButtonStyle.SECONDARY,
    content: @Composable RowScope.() -> Unit,
) {
    val (container, text) = when (style) {
        CarButtonStyle.PRIMARY -> Palette.Signal to Palette.Background
        CarButtonStyle.ACTIVE -> Palette.Good to Palette.Background
        CarButtonStyle.SECONDARY -> Color.Transparent to Palette.Text
    }
    Button(
        guarded(onClick),
        modifier.heightIn(min = TouchHeight).widthIn(min = 160.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = text),
        border = if (style == CarButtonStyle.SECONDARY) BorderStroke(2.dp, Palette.Line) else null,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
        content = content,
    )
}
