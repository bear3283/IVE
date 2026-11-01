package com.drivescore.data.signal

import android.os.SystemClock
import android.util.Log
import com.drivescore.scoring.SignalCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 시나리오 재생. [autoStart]가 false면(하이브리드) [beginClock]까지 대기한다.
 * 시연자 안내는 화면에 띄우지 않고 logcat DemoCue 태그로만 출력한다.
 */
class ReplaySignalSource(
    private val player: ScenarioPlayer,
    private val scope: CoroutineScope,
    private val autoStart: Boolean = true,
) : VehicleSignalSource {
    private val _state = MutableStateFlow(player.stateAt(0.0))
    override val state: StateFlow<VehicleState> = _state
    override val capabilities = MutableStateFlow(SignalCapabilities())
    private var job: Job? = null
    @Volatile private var running = false
    private var scenarioT = 0.0
    private var nextCue = 0

    fun beginClock() {
        if (!running) {
            scenarioT = player.driveStartSec
            running = true
        }
    }

    /** 주행이 끝나면 처음으로 돌아간다. 다음 D에서 같은 코스를 다시 달린다. */
    fun resetClock() {
        if (running && !autoStart) {
            running = false
            scenarioT = 0.0
            nextCue = 0
            _state.value = player.stateAt(0.0)
        }
    }

    override fun start() {
        if (autoStart) running = true
        val speed = player.scenario.playbackSpeed
        var last = SystemClock.elapsedRealtime()
        val cues = player.scenario.scriptedEvents
        job = scope.launch {
            while (isActive) {
                if (running) {
                    while (nextCue < cues.size && cues[nextCue].t <= scenarioT) {
                        Log.i("DemoCue", cues[nextCue++].note)
                    }
                    _state.value = player.stateAt(scenarioT)
                }
                delay(TICK_MS)
                // delay 횟수로 세면 실제 시간보다 느려져 누적거리가 시나리오와 어긋난다.
                val now = SystemClock.elapsedRealtime()
                if (running) scenarioT += (now - last) / 1000.0 * speed
                last = now
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    private companion object {
        const val TICK_MS = 50L
    }
}
