package com.drivescore.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class DriverProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 시연용 등급 시드. 계산된 등급보다 낮으면 무시된다. */
    val minGrade: String = "BEGINNER",
)

@Entity
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val destLat: Double,
    val destLng: Double,
    /** MarkerType 이름을 쉼표로 이은 회전 순서. */
    val turns: String,
)

@Entity(indices = [Index("profileId"), Index("courseId")])
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val courseId: Long?,
    val startedAtMs: Long,
    val durationSec: Double,
    val distanceM: Double,
    val total: Int,
    val difficulty: Double,
    val grade: String,
    /** "이번엔 기록하지 마". 등급 계산에서 뺀다. */
    val excluded: Boolean = false,
    /** ItemScore 목록 JSON. */
    val itemsJson: String,
)

@Entity(indices = [Index("tripId")])
data class TripEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val category: String,
    val reason: String,
    val t: Double,
    val odoM: Double,
    val severity: Double,
    val observedJson: String,
    val lat: Double?,
    val lng: Double?,
)

@Entity(indices = [Index("tripId")])
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val t: Double,
    val odoM: Double,
    val speedKph: Double,
    val steeringDeg: Double?,
    val turnSignal: String?,
    val brakeOn: Boolean?,
    val lat: Double?,
    val lng: Double?,
)

@Dao
interface DriveDao {
    @Query("SELECT * FROM DriverProfile ORDER BY id LIMIT 1") suspend fun firstProfile(): DriverProfile?
    @Insert suspend fun insert(p: DriverProfile): Long
    @Update suspend fun update(p: DriverProfile)
    @Insert suspend fun insert(c: Course): Long
    @Query("SELECT * FROM Course WHERE profileId = :profileId") suspend fun courses(profileId: Long): List<Course>
    @Insert suspend fun insert(t: Trip): Long
    @Insert suspend fun insertEvents(e: List<TripEvent>)
    @Insert suspend fun insertTrack(p: List<TrackPoint>)
    @Query("SELECT total FROM Trip WHERE profileId = :profileId AND excluded = 0 ORDER BY startedAtMs")
    suspend fun totals(profileId: Long): List<Int>
    @Query("SELECT * FROM Trip WHERE profileId = :profileId ORDER BY startedAtMs DESC LIMIT 20")
    fun recentTrips(profileId: Long): Flow<List<Trip>>
    @Query("SELECT * FROM Trip WHERE id = :id") suspend fun trip(id: Long): Trip?
    @Query("SELECT * FROM TripEvent WHERE tripId = :tripId ORDER BY t") suspend fun events(tripId: Long): List<TripEvent>
    @Query("SELECT * FROM TrackPoint WHERE tripId = :tripId ORDER BY t") suspend fun track(tripId: Long): List<TrackPoint>
    /** 지난번 기록. 코스를 알면 같은 코스끼리 비교한다. */
    @Query("SELECT * FROM Trip WHERE profileId = :profileId AND excluded = 0 AND startedAtMs < :before AND (:courseId IS NULL OR courseId = :courseId) ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun previousTrip(profileId: Long, before: Long, courseId: Long?): Trip?
    @Query("SELECT total FROM Trip WHERE profileId = :profileId AND excluded = 0 AND startedAtMs < :before")
    suspend fun totalsBefore(profileId: Long, before: Long): List<Int>

    @Query("SELECT id FROM Trip WHERE profileId = :profileId ORDER BY startedAtMs DESC LIMIT 1") suspend fun latestTripId(profileId: Long): Long?
    @Query("SELECT MAX(total) FROM Trip WHERE courseId = :courseId AND excluded = 0") suspend fun bestTotal(courseId: Long): Int?
    @Query("UPDATE Trip SET excluded = :excluded WHERE id = :id") suspend fun setExcluded(id: Long, excluded: Boolean)

    @Transaction
    suspend fun saveTrip(t: Trip, events: (Long) -> List<TripEvent>, track: (Long) -> List<TrackPoint>): Long {
        val id = insert(t)
        insertEvents(events(id))
        insertTrack(track(id))
        return id
    }
}

@Database(entities = [DriverProfile::class, Course::class, Trip::class, TripEvent::class, TrackPoint::class], version = 1, exportSchema = false)
abstract class DriveDatabase : RoomDatabase() {
    abstract fun dao(): DriveDao
}
