package com.drivescore.domain

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.drivescore.data.db.*
import com.drivescore.data.navi.Destination
import com.drivescore.data.navi.NaviSource
import com.drivescore.data.navi.PleosNaviSource
import com.drivescore.data.navi.ReplayNaviSource
import com.drivescore.data.signal.*
import com.drivescore.scoring.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

/** 내비가 알려 준 지금 경로. */
data class RouteInfo(val destination: Destination, val markers: List<RouteMarker>)

/** 주행 중 위치 진행. F-06 예고가 쓴다. */
data class TripProgress(val odoM: Double = 0.0, val speedKph: Double = 0.0)

/** F-03 주행 기록. 10Hz로 샘플을 파일에 쌓고, P 진입 시 채점해 Room에 저장한다. 오프라인으로 동작한다. */
@Singleton
class TripRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DriveDao,
    private val settings: RecorderSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var signal: VehicleSignalSource? = null
    private var navi: NaviSource? = null

    private val _session = MutableStateFlow(Session.IDLE)
    val session: StateFlow<Session> = _session.asStateFlow()
    private val _gear = MutableStateFlow<Gear?>(null)
    /** null이면 기어를 모르는 상태. 화면은 닫힌 쪽으로 판단한다. */
    val gear: StateFlow<Gear?> = _gear.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _lastTripId = MutableStateFlow<Long?>(null)
    val lastTripId: StateFlow<Long?> = _lastTripId.asStateFlow()
    private val _profileId = MutableStateFlow<Long?>(null)
    val profileId: StateFlow<Long?> = _profileId.asStateFlow()

    private val _route = MutableStateFlow<RouteInfo?>(null)
    val route: StateFlow<RouteInfo?> = _route.asStateFlow()
    private val _progress = MutableStateFlow(TripProgress())
    val progress: StateFlow<TripProgress> = _progress.asStateFlow()
    /** "이번엔 기록하지 마"를 출발 전에 말한 경우. 다음 주행 한 건에 적용한다. */
    @Volatile private var skipNextTrip = false

    private val tripsDir get() = File(context.filesDir, "trips").apply { mkdirs() }

    /** 현재 설정의 소스로 기록을 (다시) 시작한다. */
    fun restart() {
        stop()
        loop = scope.launch {
            _profileId.value = ensureProfile().id
            recoverUnfinished()
            val (s, n) = createSources()
            signal = s
            navi = n
            _session.value = Session.IDLE
            runLoop(s, n)
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        signal?.stop()
        navi?.stop()
        signal = null
        navi = null
        _route.value = null
    }

    fun dismissResult() {
        _session.value = SessionStateMachine.next(_session.value, SessionEvent.Dismissed)
    }

    /** 시연 프로필 시드: 숙련 기준으로 채점되도록 한다(D8). */
    suspend fun setDemoSeed(enabled: Boolean) {
        val p = ensureProfile()
        dao.update(p.copy(minGrade = if (enabled) Grade.SKILLED.name else Grade.BEGINNER.name))
    }

    suspend fun isDemoSeed() = ensureProfile().minGrade == Grade.SKILLED.name

    private suspend fun ensureProfile(): DriverProfile =
        dao.firstProfile() ?: DriverProfile(name = "운전자 1").let { it.copy(id = dao.insert(it)) }

    /** 지금 경로와 같은 저장 코스. 새 코스를 만들지는 않는다. */
    suspend fun findCourseId(): Long? {
        val route = _route.value ?: return null
        val profile = _profileId.value ?: return null
        val turns = route.markers.filter { it.type in TURN_TYPES }.map { it.type }
        return CourseMatcher().findMatch(CourseSignature(route.destination.lat, route.destination.lng, turns), signatures(profile))
    }

    private suspend fun signatures(profileId: Long) = dao.courses(profileId).associate { c ->
        c.id to CourseSignature(c.destLat, c.destLng, c.turns.split(",").filter { it.isNotEmpty() }.map(MarkerType::valueOf))
    }

    /** 표시 등급과 다음 등급 조건. 시연 시드를 반영한다. */
    suspend fun gradeProgress(): Pair<Grade, GradeProgress> {
        val profile = ensureProfile()
        val progress = GradeEngine().evaluate(dao.totals(profile.id))
        return maxOf(progress.current, Grade.valueOf(profile.minGrade)) to progress
    }

    /** @param tripId 도착 후 결과를 보고 있으면 그 주행. 없으면 다음 주행을 제외한다. */
    suspend fun skipRecord(tripId: Long?) {
        if (tripId != null) dao.setExcluded(tripId, true) else skipNextTrip = true
    }

    fun canListen() = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun createSources(): Pair<VehicleSignalSource, NaviSource> {
        fun player() = ScenarioPlayer(
            Scenario.parse(context.assets.open("scenarios/${settings.scenario}").bufferedReader().use { it.readText() }),
        )
        return when (settings.mode) {
            SourceMode.LIVE -> LiveSignalSource(context) to PleosNaviSource(context)
            SourceMode.REPLAY -> player().let { ReplaySignalSource(it, scope) to ReplayNaviSource(it) }
            SourceMode.HYBRID -> player().let {
                val replay = ReplaySignalSource(it, scope, autoStart = false)
                HybridSignalSource(replay, LiveSignalSource(context, readSpeed = false), scope) to ReplayNaviSource(it)
            }
        }
    }

    private suspend fun runLoop(source: VehicleSignalSource, nav: NaviSource) {
        var t = 0.0
        var odo = 0.0
        var writer: PrintWriter? = null
        var file: File? = null
        var startedAtMs = 0L
        var writtenMarkers: List<RouteMarker> = emptyList()
        var previous = VehicleState()
        var last = SystemClock.elapsedRealtime()
        var flushAt = 0L
        val samples = ArrayList<SignalSample>()

        withContext(Dispatchers.Main) { source.start() }
        nav.start { odo }
        addLog("소스 시작: ${settings.mode}")

        try {
            while (currentCoroutineContext().isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000.0
                last = now
                val st = source.state.value
                logChanges(previous, st)
                previous = st
                _gear.value = st.gear
                val dest = nav.destination.value
                val routeNow = dest?.let { RouteInfo(it, nav.markers.value) }
                if (routeNow != _route.value) _route.value = routeNow

                val destinationEvent = if (nav.destination.value != null) SessionEvent.DestinationSet else SessionEvent.DestinationCleared
                var session = SessionStateMachine.next(_session.value, destinationEvent)
                val speed = st.speedKph ?: 0.0
                st.gear?.let { session = SessionStateMachine.next(session, SessionEvent.Vehicle(it, speed)) }

                if (session == Session.DRIVING && _session.value != Session.DRIVING) {
                    t = 0.0
                    odo = 0.0
                    samples.clear()
                    writtenMarkers = emptyList()
                    startedAtMs = System.currentTimeMillis()
                    file = File(tripsDir, "$startedAtMs.samples")
                    writer = PrintWriter(file!!.bufferedWriter())
                    addLog("주행 시작")
                }
                _session.value = session

                if (session == Session.DRIVING) {
                    val sample = SignalSample(t, odo, speed, st.steeringDeg, st.turnSignal, st.brakeOn, st.gear ?: Gear.D, st.lat, st.lng)
                    samples += sample
                    writer?.println(RecordingCodec.encode(sample))
                    val markers = nav.markers.value
                    if (markers !== writtenMarkers) {
                        markers.forEach { writer?.println(RecordingCodec.encode(it)) }
                        writtenMarkers = markers
                    }
                    if (now >= flushAt) {
                        writer?.flush()
                        flushAt = now + 1000
                    }
                    t += dt
                    odo += speed / 3.6 * dt
                    _progress.value = TripProgress(odo, speed)
                } else if (session == Session.SCORING) {
                    writer?.close()
                    writer = null
                    val distance = samples.lastOrNull()?.odoM ?: 0.0
                    if (distance >= ScoringConfig().minTripDistanceM) {
                        val id = scoreAndSave(samples.toList(), writtenMarkers, source.capabilities.value, startedAtMs, nav)
                        _lastTripId.value = id
                        addLog("주행 저장: #$id")
                        _session.value = SessionStateMachine.next(Session.SCORING, SessionEvent.ScoringDone)
                    } else {
                        addLog("짧은 주행 버림: ${distance.toInt()}m")
                        skipNextTrip = false
                        _session.value = Session.IDLE
                    }
                    file?.delete()
                    file = null
                }
            }
        } finally {
            // 취소돼도 파일은 지우지 않는다. 다음 시작 때 복구한다.
            writer?.close()
        }
    }

    private suspend fun scoreAndSave(
        samples: List<SignalSample>,
        markers: List<RouteMarker>,
        caps: SignalCapabilities,
        startedAtMs: Long,
        nav: NaviSource?,
    ): Long {
        val profile = ensureProfile()
        val computed = GradeEngine().evaluate(dao.totals(profile.id)).current
        val grade = maxOf(computed, Grade.valueOf(profile.minGrade))
        val result = TripScorer().score(samples, markers, caps, GradeRules.forGrade(grade))
        val courseId = nav?.destination?.value?.let { matchCourse(profile.id, it.name, it.lat, it.lng, markers) }
        val track = TrackReducer.reduce(samples, result.penalties)
        val trip = Trip(
            profileId = profile.id,
            courseId = courseId,
            startedAtMs = startedAtMs,
            durationSec = samples.lastOrNull()?.t ?: 0.0,
            distanceM = samples.lastOrNull()?.odoM ?: 0.0,
            total = result.total,
            difficulty = result.difficulty,
            grade = grade.name,
            itemsJson = TripCodec.encodeItems(result.items),
            excluded = skipNextTrip.also { skipNextTrip = false },
        )
        return dao.saveTrip(
            trip,
            { id -> result.penalties.map { it.toEntity(id) } },
            { id -> track.map { TrackPoint(0, id, it.t, it.odoM, it.speedKph, it.steeringDeg, it.turnSignal?.name, it.brakeOn, it.lat, it.lng) } },
        )
    }

    private suspend fun matchCourse(profileId: Long, name: String, lat: Double, lng: Double, markers: List<RouteMarker>): Long {
        val turns = markers.filter { it.type in TURN_TYPES }.map { it.type }
        return CourseMatcher().findMatch(CourseSignature(lat, lng, turns), signatures(profileId))
            ?: dao.insert(Course(profileId = profileId, name = name, destLat = lat, destLng = lng, turns = turns.joinToString(",")))
    }

    /** 이전 실행이 주행 중 종료됐으면 남은 샘플로 채점한다. */
    private suspend fun recoverUnfinished() {
        tripsDir.listFiles { f -> f.name.endsWith(".samples") }?.forEach { f ->
            val decoded = RecordingCodec.decode(f.readLines())
            if ((decoded.samples.lastOrNull()?.odoM ?: 0.0) >= ScoringConfig().minTripDistanceM) {
                val startedAt = f.nameWithoutExtension.toLongOrNull() ?: f.lastModified()
                val id = scoreAndSave(decoded.samples, decoded.markers, SignalCapabilities(), startedAt, null)
                addLog("중단된 주행 복구: #$id")
            }
            f.delete()
        }
    }

    private fun logChanges(a: VehicleState, b: VehicleState) {
        if (a.gear != b.gear) addLog("기어 ${b.gear}")
        if (a.turnSignal != b.turnSignal) addLog("깜빡이 ${b.turnSignal}")
        if (a.brakeOn != b.brakeOn) addLog("브레이크 ${b.brakeOn}")
        val bucket = { d: Double? -> d?.let { (it / 10).toInt() } }
        if (bucket(a.steeringDeg) != bucket(b.steeringDeg)) addLog("조향 ${b.steeringDeg?.toInt()}°")
    }

    private fun addLog(line: String) {
        Log.d("DriveEvent", line)
        _log.value = (_log.value + line).takeLast(LOG_SIZE)
    }

    private fun Penalty.toEntity(tripId: Long) = TripEvent(
        tripId = tripId, category = category.name, reason = reason.name, t = t, odoM = odoM, severity = severity,
        observedJson = JsonObject(observed.mapValues { JsonPrimitive(it.value) }).toString(), lat = lat, lng = lng,
    )

    private companion object {
        const val TICK_MS = 100L
        const val LOG_SIZE = 40
        val TURN_TYPES = setOf(MarkerType.TURN_LEFT, MarkerType.TURN_RIGHT, MarkerType.U_TURN)
    }
}
