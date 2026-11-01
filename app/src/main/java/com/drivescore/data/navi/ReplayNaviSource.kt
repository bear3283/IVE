package com.drivescore.data.navi

import com.drivescore.data.signal.ScenarioPlayer
import com.drivescore.scoring.RouteMarker
import kotlinx.coroutines.flow.MutableStateFlow

class ReplayNaviSource(private val player: ScenarioPlayer) : NaviSource {
    override val destination = MutableStateFlow<Destination?>(null)
    override val markers = MutableStateFlow<List<RouteMarker>>(emptyList())

    override fun start(odoM: () -> Double) {
        val d = player.scenario.destination
        destination.value = Destination(d.name, d.lat, d.lng)
        markers.value = player.markers()
    }

    override fun stop() = Unit
}
