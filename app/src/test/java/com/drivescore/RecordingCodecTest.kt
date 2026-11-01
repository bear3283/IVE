package com.drivescore

import com.drivescore.domain.MarkerMerger
import com.drivescore.domain.RecordingCodec
import com.drivescore.domain.TrackReducer
import com.drivescore.scoring.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingCodecTest {
    @Test fun `샘플과 지점을 왕복하고 깨진 마지막 줄은 버린다`() {
        val s = SignalSample(1.5, 20.0, 33.0, null, TurnSignal.LEFT, true, Gear.D, 37.5, 127.0)
        val m = RouteMarker("t", MarkerType.TURN_LEFT, 90.0, 50)
        val r = RecordingCodec.decode(listOf(RecordingCodec.encode(s), RecordingCodec.encode(m), "S,1.6,2"))
        assertEquals(listOf(s), r.samples)
        assertEquals(listOf(m), r.markers)
    }

    @Test fun `같은 id의 지점은 최신 값으로 갱신`() {
        val a = RouteMarker("c", MarkerType.SPEED_CAMERA, 100.0, 50)
        val b = a.copy(odoM = 110.0)
        assertEquals(listOf(b), MarkerMerger.merge(listOf(a), listOf(b)))
    }

    @Test fun `거리가 가까운 같은 타입의 회전 지점은 하나로 합친다`() {
        val a = RouteMarker("x", MarkerType.TURN_LEFT, 100.0)
        val b = RouteMarker("y", MarkerType.TURN_LEFT, 130.0)
        val c = RouteMarker("z", MarkerType.TURN_LEFT, 500.0)
        assertEquals(listOf(130.0, 500.0), MarkerMerger.merge(listOf(a), listOf(b, c)).map { it.odoM })
    }

    @Test fun `궤적은 1Hz로 줄이되 감점 전후는 원본을 남긴다`() {
        val samples = (0 until 1000).map { SignalSample(it / 10.0, it.toDouble(), 30.0) }
        val p = Penalty(Category.SMOOTH, PenaltyReason.HARD_BRAKE, 50.0, 500.0, 1.0)
        val out = TrackReducer.reduce(samples, listOf(p), windowSec = 10.0)
        assertTrue(out.count { it.t in 40.0..60.0 } >= 200)
        assertTrue(out.count { it.t < 30.0 } in 28..32)
        assertEquals(out.sortedBy { it.t }, out)
    }
}
