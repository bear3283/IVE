package com.drivescore.domain

import android.content.Context
import android.util.Log
import com.drivescore.R
import com.drivescore.data.db.DriveDao
import com.drivescore.data.voice.Heard
import com.drivescore.data.voice.VoiceInput
import com.drivescore.scoring.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** 출발 전 브리핑, 주행 중 예고, 도착 후 디브리핑과 음성 명령을 잇는다. */
@Singleton
class VoiceCoach @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: TripRecorder,
    private val dao: DriveDao,
    private val director: VoiceDirector,
    private val input: VoiceInput,
    private val gate: DriveGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val lines = VoiceLines { id, args -> context.getString(id, *args) }

    private val _briefing = MutableStateFlow<Briefing?>(null)
    val briefing: StateFlow<Briefing?> = _briefing.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    /** 화면을 바꿔야 하는 명령. 화면이 처리한다. */
    private val _commands = MutableSharedFlow<IntentMatch>(extraBufferCapacity = 4)
    val commands: SharedFlow<IntentMatch> = _commands.asSharedFlow()

    val caption get() = director.caption
    private var started = false
    private var interaction: Job? = null
    private var briefedDestination: String? = null
    private var lastReplayReason: PenaltyReason? = null
    /** 지금 보고 있는 주행과 리플레이의 현재 감점. 화면이 알려 준다. */
    @Volatile var focusDetails: TripDetails? = null
    @Volatile var focusPenalty: Penalty? = null
    /** 네/아니오를 기다리는 "감점 이유 들을까요?" 질문. */
    @Volatile private var askedWhy = false

    fun start() {
        if (started) return
        started = true
        scope.launch { briefingLoop() }
        scope.launch { hazardLoop() }
        scope.launch {
            gate.state.collect {
                if (it.gear != null && it.gear != Gear.P) cancelInteraction()
            }
        }
    }

    private suspend fun briefingLoop() {
        combine(recorder.session, recorder.route, gate.state) { session, route, g ->
            Triple(session, route, g.gear == Gear.P)
        }.distinctUntilChanged().collectLatest { (session, route, parked) ->
            if (route == null) {
                _briefing.value = null
                briefedDestination = null
                return@collectLatest
            }
            if (session != Session.PRE_DRIVE) return@collectLatest
            val b = loadBriefing(route)
            _briefing.value = b
            // 같은 목적지는 한 번만 읽는다. 다시 들으려면 "오늘 미션 뭐야".
            if (parked && briefedDestination != b.destination) {
                briefedDestination = b.destination
                director.speak(VoiceKind.BRIEFING, lines.briefing(b))
            }
        }
    }

    private suspend fun loadBriefing(route: RouteInfo): Briefing {
        val turns = route.markers.count { it.type in TURN_TYPES }
        val cameras = route.markers.count { it.type == MarkerType.SPEED_CAMERA || it.type == MarkerType.SECTION_START }
        val profile = recorder.profileId.value
        val course = recorder.findCourseId()
        val last = if (profile != null && course != null) dao.previousTrip(profile, Long.MAX_VALUE, course) else null
        val penalty = last?.let { t -> dao.events(t.id).map(TripCodec::toPenalty).maxByOrNull { it.deduction } }
        return Briefing(
            route.destination.name, route.markers, turns, cameras, course != null,
            last?.total, course?.let { dao.bestTotal(it) }, penalty,
        )
    }

    /** F-06. 주행이 시작되면 같은 코스 지난 주행의 감점 지점을 불러와 거리로 예고한다. */
    private suspend fun hazardLoop() {
        recorder.session.map { it == Session.DRIVING }.distinctUntilChanged().collectLatest { driving ->
            if (!driving) return@collectLatest
            val route = recorder.route.value ?: return@collectLatest
            val profile = recorder.profileId.value ?: return@collectLatest
            val course = recorder.findCourseId() ?: return@collectLatest
            val startedAt = System.currentTimeMillis()
            val last = dao.previousTrip(profile, startedAt, course) ?: return@collectLatest
            val hazards = dao.events(last.id).map { it.odoM }
            if (hazards.isEmpty()) return@collectLatest
            val alerter = HazardAlerter(hazards, route.markers.filter { it.type in TURN_TYPES }.map { it.odoM })
            Log.d(TAG, "예고 지점 ${hazards.size}곳 (지난 주행 #${last.id})")
            while (true) {
                delay(POLL_MS)
                val p = recorder.progress.value
                if (alerter.poll(p.odoM, p.speedKph)) {
                    // 발화가 끝나길 기다리지 않는다. 거리는 계속 봐야 한다.
                    scope.launch { director.speak(VoiceKind.HAZARD, lines.hazard()) }
                }
            }
        }
    }

    /** F-10. 말을 마치면 대답을 5초 듣는다. */
    fun debrief(d: TripDetails) {
        launchInteraction {
            val spoken = director.speak(VoiceKind.DEBRIEF, lines.debrief(d.trip.total, d.previousTotal, d.penalties.size))
            if (spoken && d.penalties.isNotEmpty() && recorder.canListen()) {
                askedWhy = true
                listenOnce()
            }
        }
    }

    /** 정차 중 마이크 버튼. */
    fun onMicPressed() {
        if (_listening.value) {
            cancelInteraction()
            return
        }
        launchInteraction { listenOnce() }
    }

    private suspend fun listenOnce() {
        director.stop()
        _listening.value = true
        val heard = try {
            input.listen(LISTEN_MS)
        } finally {
            _listening.value = false
        }
        Log.d(TAG, "들음: $heard")
        when (heard) {
            is Heard.Text -> handle(heard.text)
            Heard.Nothing -> askedWhy = false
            Heard.Unavailable -> {
                askedWhy = false
                director.speak(VoiceKind.ANSWER, context.getString(R.string.voice_input_unavailable))
            }
        }
    }

    /** 인식된 문장을 처리한다. 정차 중에만 호출된다. */
    fun onTranscript(text: String) = launchInteraction { handle(text) }

    private suspend fun handle(text: String) {
        val match = IntentMatcher.match(text)
        Log.d(TAG, "의도: \"$text\" → $match")
        val asked = askedWhy
        askedWhy = false
        val details = focusDetails ?: latestDetails()
        when (match?.intent) {
            null -> say(lines.notHeard())
            Intent.YES -> if (asked) sayWhy(details) else say(lines.notHeard())
            Intent.NO -> director.stop()
            Intent.WHY -> sayWhy(details)
            Intent.MISSION -> _briefing.value?.let { say(lines.briefing(it)) } ?: say(context.getString(R.string.voice_no_mission))
            Intent.COMPARE -> details?.let { say(lines.compare(it.trip.total, it.previousTotal)) } ?: say(context.getString(R.string.voice_no_trip))
            Intent.GRADE -> {
                val (grade, progress) = recorder.gradeProgress()
                say(lines.grade(grade, progress))
            }
            Intent.SKIP_RECORD -> {
                recorder.skipRecord(focusDetails?.trip?.id)
                say(lines.skipDone())
            }
            Intent.SWITCH_DRIVER -> say(lines.switchUnavailable())
            Intent.REPLAY -> _commands.emit(match)
            Intent.NEXT -> _commands.emit(match)
        }
    }

    /** 결과 화면을 닫을 때. 지난 문장이 홈에 남지 않게 한다. */
    fun leaveResult() {
        cancelInteraction()
        director.clearCaption()
        focusDetails = null
        focusPenalty = null
    }

    /** 화면에 주행이 없으면(홈) 가장 최근 주행을 답한다. */
    private suspend fun latestDetails(): TripDetails? =
        recorder.profileId.value?.let { dao.latestTripId(it) }?.let { dao.loadTripDetails(it) }

    private suspend fun sayWhy(d: TripDetails?) {
        val focus = focusPenalty
        when {
            focus != null -> say(lines.reason(focus))
            d != null -> say(lines.why(d.penalties))
            else -> say(context.getString(R.string.voice_no_trip))
        }
    }

    private suspend fun say(text: String) { director.speak(VoiceKind.ANSWER, text) }

    fun sayNextOnlyInReplay() = launchInteraction { say(lines.nextOnlyInReplay()) }

    /** 같은 이유를 연달아 읽지 않는다(급정지는 한 번에 여러 건으로 잡힐 수 있다). */
    fun speakReplay(p: Penalty) {
        if (_listening.value || p.reason == lastReplayReason) return
        lastReplayReason = p.reason
        scope.launch { director.speak(VoiceKind.REPLAY, lines.reason(p)) }
    }

    fun resetReplaySpeech() { lastReplayReason = null }

    fun cancelInteraction() {
        interaction?.cancel()
        interaction = null
        input.stop()
        director.stop()
        askedWhy = false
    }

    private fun launchInteraction(block: suspend () -> Unit) {
        interaction?.cancel()
        interaction = scope.launch { block() }
    }

    private companion object {
        const val TAG = "VoiceCoach"
        const val POLL_MS = 200L
        const val LISTEN_MS = 5_000L
        val TURN_TYPES = setOf(MarkerType.TURN_LEFT, MarkerType.TURN_RIGHT, MarkerType.U_TURN)
    }
}
