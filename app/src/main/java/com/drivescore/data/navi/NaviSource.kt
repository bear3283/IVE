package com.drivescore.data.navi

import com.drivescore.scoring.RouteMarker
import kotlinx.coroutines.flow.StateFlow

data class Destination(val name: String, val lat: Double, val lng: Double)

interface NaviSource {
    val destination: StateFlow<Destination?>
    /** 주행 누적거리 기준으로 환산된 안내 지점. */
    val markers: StateFlow<List<RouteMarker>>
    /** @param odoM 현재 주행 누적거리. SDK의 남은 거리를 누적거리로 바꿀 때 쓴다. */
    fun start(odoM: () -> Double)
    fun stop()
}
