package com.drivescore

import com.drivescore.data.db.TrackPoint
import com.drivescore.data.db.TripEvent
import com.drivescore.domain.GateState
import com.drivescore.domain.ReplayGeometry
import com.drivescore.domain.TripCodec
import com.drivescore.scoring.*
import kotlin.test.*

class TripDetailsTest {
    private fun pt(t: Double, odo: Double, steer: Double = 0.0, lat: Double? = null, lng: Double? = null) =
        TrackPoint(0, 1, t, odo, 30.0, steer, "OFF", false, lat, lng)

    @Test fun `항목 점수는 저장 후 그대로 읽힌다`() {
        val items = listOf(ItemScore(Category.TURN, 25.0, 12.5, 3, false), ItemScore(Category.CAMERA, 15.0, 15.0, 5, true))
        assertEquals(items, TripCodec.decodeItems(TripCodec.encodeItems(items)))
    }

    @Test fun `감점 관측값을 읽는다`() {
        val e = TripEvent(0, 1, "SMOOTH", "HARD_BRAKE", 87.7, 900.0, 1.0, """{"mps2":4.6}""", null, null)
        val p = TripCodec.toPenalty(e)
        assertEquals(PenaltyReason.HARD_BRAKE, p.reason)
        assertEquals(4.6, p.observed["mps2"])
    }

    @Test fun `위치가 있으면 북쪽이 위로 간다`() {
        val out = ReplayGeometry.project(listOf(pt(0.0, 0.0, lat = 37.0, lng = 127.0), pt(1.0, 10.0, lat = 37.001, lng = 127.0)))
        assertEquals(0.0, out[0].y)
        assertEquals(110.54, out[1].y, 0.01)
    }

    @Test fun `위치가 없으면 조향으로 추정하고 오른쪽 조향은 x가 늘어난다`() {
        val out = ReplayGeometry.project((0..20).map { pt(it.toDouble(), it * 5.0, steer = 200.0) })
        assertTrue(out.last().x > 10.0, "x=${out.last().x}")
        val straight = ReplayGeometry.project((0..20).map { pt(it.toDouble(), it * 5.0) })
        assertEquals(0.0, straight.last().x, 1e-9)
        assertEquals(100.0, straight.last().y, 1e-9)
    }

    @Test fun `시간 검색은 범위를 벗어나지 않는다`() {
        val ts = doubleArrayOf(0.0, 1.0, 2.0)
        assertEquals(0, ReplayGeometry.indexAt(ts, -1.0))
        assertEquals(1, ReplayGeometry.indexAt(ts, 1.5))
        assertEquals(2, ReplayGeometry.indexAt(ts, 9.0))
    }

    @Test fun `P단이고 제한이 없을 때만 콘텐츠를 연다`() {
        assertTrue(GateState(Gear.P, false).canShowContent)
        assertFalse(GateState(Gear.D, false).canShowContent)
        assertFalse(GateState(Gear.P, true).canShowContent)
        assertFalse(GateState(null, false).canShowContent)
    }

    @Test fun `기어를 모르는 시작 직후에는 표지를 띄우지 않는다`() {
        assertFalse(GateState(null, false).needsCover)
        assertTrue(GateState(Gear.R, false).needsCover)
        assertTrue(GateState(Gear.P, true).needsCover)
    }
}
