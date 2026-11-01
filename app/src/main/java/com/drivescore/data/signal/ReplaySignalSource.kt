package com.drivescore.data.signal

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

    fun beginClock() {
        if (!running) {
            scenarioT = player.driveStartSec
            running = true
        }
    }

    override fun start() {
        if (autoStart) running = true
        val speed = player.scenario.playbackSpeed
        var nextCue = 0
        val cues = player.scenario.scriptedEvents
        job = scope.launch {
            while (isActive) {
                if (running) {
                    while (nextCue < cues.size && cues[nextCue].t <= scenarioT) {
                        Log.i("DemoCue", cues[nextCue++].note)
                    }
                    _state.value = player.stateAt(scenarioT)
                    scenarioT += TICK_MS / 1000.0 * speed
                }
                delay(TICK_MS)
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
