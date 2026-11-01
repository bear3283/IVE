package com.drivescore.data.signal

import com.drivescore.scoring.Gear
import com.drivescore.scoring.SignalCapabilities
import com.drivescore.scoring.TurnSignal
import kotlinx.coroutines.flow.StateFlow

/** 현재 차량 신호. 값을 받지 못한 항목은 null. */
data class VehicleState(
    val gear: Gear? = null,
    val speedKph: Double? = null,
    val steeringDeg: Double? = null,
    val turnSignal: TurnSignal? = null,
    val brakeOn: Boolean? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

interface VehicleSignalSource {
    val state: StateFlow<VehicleState>
    val capabilities: StateFlow<SignalCapabilities>
    fun start()
    fun stop()
}

enum class SourceMode { LIVE, REPLAY, HYBRID }
