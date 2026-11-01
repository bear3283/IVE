package com.drivescore.data.signal

import com.drivescore.scoring.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 차속·위치는 시나리오, 조향·깜빡이·브레이크·기어는 Virtual Console. 시나리오 시계는 기어 D에서 출발한다. */
class HybridSignalSource(
    private val replay: ReplaySignalSource,
    private val live: VehicleSignalSource,
    private val scope: CoroutineScope,
) : VehicleSignalSource {
    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state
    override val capabilities = live.capabilities
    private var job: Job? = null

    override fun start() {
        live.start()
        replay.start()
        job = scope.launch {
            combine(replay.state, live.state) { r, l ->
                if (l.gear == Gear.D) replay.beginClock()
                l.copy(speedKph = r.speedKph, lat = r.lat, lng = r.lng)
            }.collect { _state.value = it }
        }
    }

    override fun stop() {
        job?.cancel()
        replay.stop()
        live.stop()
    }
}
