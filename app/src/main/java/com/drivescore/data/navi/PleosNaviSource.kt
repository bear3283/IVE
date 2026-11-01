package com.drivescore.data.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.drivescore.domain.MarkerMerger
import com.drivescore.scoring.MarkerType
import com.drivescore.scoring.RouteMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** SDK 안내 지점에는 좌표가 없으므로 남은 거리 + 현재 누적거리로 환산한다. */
class PleosNaviSource(private val context: Context) : NaviSource {
    override val destination = MutableStateFlow<Destination?>(null)
    override val markers = MutableStateFlow<List<RouteMarker>>(emptyList())

    private var helper: NaviHelper? = null
    private var odo: () -> Double = { 0.0 }

    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() = refresh()
        override fun onDestinationChanged() = refresh()
        override fun onReRouteEnded() {
            // 재탐색: 아직 지나지 않은 지점은 버리고 다시 받는다.
            val now = odo()
            markers.update { list -> list.filter { it.odoM <= now } }
            refresh()
        }
        override fun onRouteCancelled() {
            destination.value = null
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destination.value = Destination(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude)
        }
        override fun onTBTInfo(tbtInfo: List<TBTInfo>) {
            val now = odo()
            merge(tbtInfo.mapNotNull { tbt ->
                turnMarker(tbt.type)?.let { RouteMarker("tbt-$it-${(now + tbt.distance).toInt() / 50}", it, now + tbt.distance) }
            })
        }
        override fun onCameraAlert(cameraAlerts: List<CameraAlert>) {
            val now = odo()
            merge(cameraAlerts.mapNotNull { cam ->
                cameraMarker(cam.type)?.let { RouteMarker(cam.id, it, now + cam.remainDistance, cam.speedLimit.takeIf { v -> v > 0 }) }
            })
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w("PleosNaviSource", "내비 오류: $errorCode")
        }
    }

    override fun start(odoM: () -> Double) {
        odo = odoM
        val h = NaviHelper(context).also { helper = it }
        h.initialize({
            h.addListener(listener)
            refresh()
        }, { Log.w("PleosNaviSource", "초기화 실패: ${it.message}") })
    }

    override fun stop() {
        helper?.run {
            removeListener(listener)
            release()
        }
        helper = null
    }

    private fun refresh() {
        helper?.run {
            getDestinationInfo()
            getTBTInfo()
        }
    }

    private fun merge(incoming: List<RouteMarker>) {
        markers.update { MarkerMerger.merge(it, incoming) }
    }

    private fun turnMarker(t: TurnType): MarkerType? = when (t) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> MarkerType.TURN_LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> MarkerType.TURN_RIGHT
        TurnType.TURN_UTURN -> MarkerType.U_TURN
        TurnType.TURN_ARRIVE -> MarkerType.DESTINATION
        else -> null
    }

    private fun cameraMarker(t: CameraType): MarkerType? = when (t) {
        CameraType.SPEED_CAMERA_FIX, CameraType.SPEED_CAMERA_MOV, CameraType.VARIABLE_SPEED_CAMERA,
        CameraType.SPEED_CAMERA_SCHOOL_ZONE -> MarkerType.SPEED_CAMERA
        CameraType.SPEED_CAMERA_SECTION_START -> MarkerType.SECTION_START
        CameraType.SPEED_CAMERA_SECTION_END -> MarkerType.SECTION_END
        else -> null
    }
}
