package com.drivescore.domain

import com.drivescore.data.db.DriveDao
import com.drivescore.data.db.TrackPoint
import com.drivescore.data.db.Trip
import com.drivescore.data.db.TripEvent
import com.drivescore.scoring.*
import kotlinx.serialization.json.*
import kotlin.math.cos
import kotlin.math.tan

object TripCodec {
    fun encodeItems(items: List<ItemScore>) = buildJsonArray {
        items.forEach {
            add(buildJsonObject {
                put("category", it.category.name)
                put("maxPoints", it.maxPoints)
                put("points", it.points)
                put("stars", it.stars)
                put("notApplicable", it.notApplicable)
            })
        }
    }.toString()

    fun decodeItems(json: String): List<ItemScore> = Json.parseToJsonElement(json).jsonArray.map {
        val o = it.jsonObject
        ItemScore(
            Category.valueOf(o.getValue("category").jsonPrimitive.content),
            o.getValue("maxPoints").jsonPrimitive.double,
            o.getValue("points").jsonPrimitive.double,
            o.getValue("stars").jsonPrimitive.int,
            o.getValue("notApplicable").jsonPrimitive.boolean,
        )
    }

    fun toPenalty(e: TripEvent) = Penalty(
        Category.valueOf(e.category), PenaltyReason.valueOf(e.reason), e.t, e.odoM, e.deduction,
        Json.parseToJsonElement(e.observedJson).jsonObject.mapValues { it.value.jsonPrimitive.double },
        e.lat, e.lng,
    )
}

/** 리플레이와 결과 화면이 쓰는 주행 한 건. */
data class TripDetails(
    val trip: Trip,
    val items: List<ItemScore>,
    val penalties: List<Penalty>,
    val track: List<TrackPoint>,
    val previousTotal: Int?,
    val badges: List<Badge>,
    val previousItems: List<ItemScore> = emptyList(),
)

suspend fun DriveDao.loadTripDetails(id: Long): TripDetails? {
    val trip = trip(id) ?: return null
    val items = TripCodec.decodeItems(trip.itemsJson)
    val penalties = events(id).map(TripCodec::toPenalty).sortedBy { it.t }
    val previous = previousTrip(trip.profileId, trip.startedAtMs, trip.courseId)
    return TripDetails(
        trip, items, penalties, track(id),
        previous?.total,
        BadgeRules.award(trip.total, items, penalties, trip.distanceM, totalsBefore(trip.profileId, trip.startedAtMs), trip.challengePassed, trip.lowSample),
        previous?.let { TripCodec.decodeItems(it.itemsJson) }.orEmpty(),
    )
}

/** 주행선 좌표(미터). 위치가 충분하면 위경도, 아니면 차속과 조향으로 추정한다. 지도 없는 그림이라 이 정도면 된다. */
object ReplayGeometry {
    data class Pt(val x: Double, val y: Double)

    private const val WHEELBASE_M = 2.7
    private const val STEERING_RATIO = 15.0
    private const val MIN_LOCATED = 0.8

    fun project(points: List<TrackPoint>): List<Pt> {
        val located = points.count { it.lat != null && it.lng != null }
        return if (points.isNotEmpty() && located >= points.size * MIN_LOCATED) fromLocation(points) else deadReckon(points)
    }

    private fun fromLocation(points: List<TrackPoint>): List<Pt> {
        val first = points.first { it.lat != null && it.lng != null }
        val lat0 = first.lat!!
        val lng0 = first.lng!!
        val k = cos(Math.toRadians(lat0))
        var last = Pt(0.0, 0.0)
        return points.map { p ->
            if (p.lat != null && p.lng != null) last = Pt((p.lng - lng0) * k * 111_320.0, (p.lat - lat0) * 110_540.0)
            last
        }
    }

    private fun deadReckon(points: List<TrackPoint>): List<Pt> {
        var x = 0.0
        var y = 0.0
        var heading = 0.0
        var previousOdo = points.firstOrNull()?.odoM ?: 0.0
        return points.map { p ->
            val ds = p.odoM - previousOdo
            previousOdo = p.odoM
            heading += ds / WHEELBASE_M * tan(Math.toRadians((p.steeringDeg ?: 0.0) / STEERING_RATIO))
            x += ds * kotlin.math.sin(heading)
            y += ds * cos(heading)
            Pt(x, y)
        }
    }

    /** t 이하에서 가장 가까운 점의 위치. 정렬된 시간 배열을 받는다. */
    fun indexAt(times: DoubleArray, t: Double): Int {
        val i = times.binarySearch(t)
        return (if (i >= 0) i else -i - 2).coerceIn(0, (times.size - 1).coerceAtLeast(0))
    }
}
