package com.drivescore.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.drivescore.R
import com.drivescore.data.db.DriveDao
import com.drivescore.data.db.Trip
import com.drivescore.data.signal.SourceMode
import com.drivescore.domain.DriveGate
import com.drivescore.domain.RecorderSettings
import com.drivescore.domain.Session
import com.drivescore.domain.TripDetails
import com.drivescore.domain.TripRecorder
import com.drivescore.domain.VoiceCoach
import com.drivescore.domain.VoiceLevel
import com.drivescore.domain.loadTripDetails
import com.drivescore.scoring.Intent as VoiceIntent
import com.drivescore.service.RecordingService
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 기본 줄 간격(24sp)은 큰 글자가 두 줄이 될 때 겹친다. 글자 크기에 맞춰 자동으로 잡히게 한다. */
private val AppTypography = Typography().run { copy(bodyLarge = bodyLarge.copy(lineHeight = TextUnit.Unspecified)) }

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Replay(val tripId: Long) : Screen
    /** @param fresh 방금 도착한 주행이면 디브리핑을 읽는다. */
    data class Result(val tripId: Long, val fresh: Boolean = false) : Screen
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var recorder: TripRecorder
    @Inject lateinit var settings: RecorderSettings
    @Inject lateinit var dao: DriveDao
    @Inject lateinit var gate: DriveGate
    @Inject lateinit var coach: VoiceCoach

    private val screenState = mutableStateOf<Screen>(Screen.Home)
    private var arrivedTripId: Long? = null
    private var debriefedTripId: Long? = null
    /** 시연 설정은 "설정" 제목을 길게 눌러야 보인다. 운전자가 실수로 바꾸지 않게 하기 위해서다. */
    private val demoVisible = mutableStateOf(false)
    private val replayNext = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = buildList {
            add("android.car.permission.CAR_SPEED")
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(needed.toTypedArray())
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Signal, onPrimary = Palette.Background, background = Palette.Background, surface = Palette.Background), typography = AppTypography) {
                val gateState by gate.state.collectAsStateWithLifecycle()
                LaunchedEffect(gateState.needsCover) {
                    if (gateState.needsCover) {
                        startActivity(Intent(this@MainActivity, DrivingCoverActivity::class.java))
                        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                    }
                }
                // 주행 중에는 화면을 구성에서 빼서 리플레이와 애니메이션을 즉시 멈춘다.
                if (gateState.canShowContent) Content() else RecordingCover()
            }
        }
    }

    @Composable
    private fun Content() {
        val session by recorder.session.collectAsStateWithLifecycle()
        val lastTripId by recorder.lastTripId.collectAsStateWithLifecycle()
        var screen by screenState
        LaunchedEffect(screen) { tapGuard.screenChanged() }

        LaunchedEffect(session, lastTripId) {
            val id = lastTripId
            if (session == Session.ARRIVED && id != null && id != arrivedTripId) {
                arrivedTripId = id
                screen = Screen.Replay(id)
            }
        }
        val listening by coach.listening.collectAsStateWithLifecycle()
        val caption by coach.caption.collectAsStateWithLifecycle()
        val close = {
            coach.leaveResult()
            recorder.dismissResult()
            screen = Screen.Home
        }
        BackHandler(screen != Screen.Home, close)

        // 화면을 바꾸는 음성 명령.
        LaunchedEffect(Unit) {
            coach.commands.collect { command ->
                val current = screenState.value
                when (command.intent) {
                    VoiceIntent.REPLAY -> coach.focusDetails?.let { screenState.value = Screen.Replay(it.trip.id) }
                    VoiceIntent.NEXT -> if (current is Screen.Replay) replayNext.tryEmit(Unit) else coach.sayNextOnlyInReplay()
                    else -> Unit
                }
            }
        }

        when (val s = screen) {
            Screen.Home -> Home(
                onOpenTrip = {
                    coach.leaveResult()
                    screen = Screen.Result(it)
                },
                onSettings = { screen = Screen.Settings },
            )
            Screen.Settings -> Settings(onBack = close)
            is Screen.Replay -> WithDetails(s.tripId, close) { d ->
                LaunchedEffect(d.trip.id) {
                    coach.focusDetails = d
                    coach.resetReplaySpeech()
                }
                val toResult = {
                    coach.focusPenalty = null
                    screen = Screen.Result(s.tripId, fresh = debriefedTripId != s.tripId && s.tripId == arrivedTripId)
                }
                // 궤적이 없으면 볼 것이 없으니 결과로 넘어간다.
                if (d.track.size < 2 || d.trip.durationSec <= 0) LaunchedEffect(Unit) { toResult() }
                else ReplayScreen(
                    d, listening, replayNext, coach::onMicPressed,
                    onActivePenalty = { p ->
                        coach.focusPenalty = p
                        p?.let(coach::speakReplay)
                    },
                    onFinished = toResult,
                )
            }
            is Screen.Result -> WithDetails(s.tripId, close) { d ->
                LaunchedEffect(d.trip.id) {
                    coach.focusDetails = d
                    if (s.fresh && debriefedTripId != d.trip.id) {
                        debriefedTripId = d.trip.id
                        coach.debrief(d)
                    }
                }
                ResultScreen(d, listening, caption, coach::onMicPressed, onReplay = {
                    coach.cancelInteraction()
                    screen = Screen.Replay(s.tripId)
                }, onClose = close)
            }
        }
    }

    @Composable
    private fun WithDetails(tripId: Long, onMissing: () -> Unit, content: @Composable (TripDetails) -> Unit) {
        var details by remember(tripId) { mutableStateOf<TripDetails?>(null) }
        LaunchedEffect(tripId) {
            details = dao.loadTripDetails(tripId)
            if (details == null) onMissing()
        }
        Box(Modifier.fillMaxSize().background(Palette.Background)) { details?.let { content(it) } }
    }

    private fun startRecording() {
        startForegroundService(Intent(this, RecordingService::class.java))
    }

    @Composable
    private fun Home(onOpenTrip: (Long) -> Unit, onSettings: () -> Unit) {
        val profileId by recorder.profileId.collectAsStateWithLifecycle()
        val trips by remember(profileId) {
            profileId?.let(dao::recentTrips) ?: emptyFlow()
        }.collectAsStateWithLifecycle(emptyList())
        val briefing by coach.briefing.collectAsStateWithLifecycle()
        val listening by coach.listening.collectAsStateWithLifecycle()
        val caption by coach.caption.collectAsStateWithLifecycle()

        Row(Modifier.fillMaxSize().background(Palette.Background).padding(48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TouchGap)) {
                BriefingPanel(briefing, Modifier.fillMaxWidth().weight(1f))
                VoiceBar(listening, caption, stringResource(R.string.mic_hint_home), coach::onMicPressed, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TouchGap)) {
                if (trips.isEmpty()) {
                    Text(stringResource(R.string.trips_empty), color = Palette.Muted, fontSize = 30.sp, modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(trips, key = Trip::id) { t -> TripRow(t) { onOpenTrip(t.id) } }
                    }
                }
                CarButton(stringResource(R.string.action_settings), onSettings, Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun TripRow(trip: Trip, onClick: () -> Unit) {
        val date = remember(trip.startedAtMs) { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(trip.startedAtMs)) }
        CarButtonFrame(onClick, Modifier.fillMaxWidth()) {
            Text("${trip.total}", color = Palette.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.points_unit), color = Palette.Muted, fontSize = 26.sp, modifier = Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(date, color = Palette.Muted, fontSize = 28.sp)
        }
    }

    @Composable
    private fun Settings(onBack: () -> Unit) {
        var mode by remember { mutableStateOf(settings.mode) }
        var scenario by remember { mutableStateOf(settings.scenario) }
        var level by remember { mutableStateOf(settings.voiceLevel) }
        var seed by remember { mutableStateOf(false) }
        var demo by demoVisible
        LaunchedEffect(Unit) { seed = recorder.isDemoSeed() }

        Row(Modifier.fillMaxSize().background(Palette.Background).padding(48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TouchGap)) {
                Text(
                    stringResource(R.string.action_settings), color = Palette.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { demo = !demo }) },
                )
                Text(stringResource(R.string.voice_level_title), color = Palette.Muted, fontSize = 30.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(TouchGap)) {
                    listOf(VoiceLevel.ALL to R.string.voice_level_all, VoiceLevel.KEY to R.string.voice_level_key, VoiceLevel.OFF to R.string.voice_level_off).forEach { (v, label) ->
                        Choice(stringResource(label), level == v) {
                            level = v
                            settings.voiceLevel = v
                        }
                    }
                }
                Text(stringResource(R.string.limits_notice), color = Palette.Muted, fontSize = 28.sp)
                Spacer(Modifier.weight(1f))
                CarButton(stringResource(R.string.action_back), onBack, Modifier.fillMaxWidth(0.5f), CarButtonStyle.PRIMARY)
            }
            if (demo) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TouchGap), horizontalAlignment = Alignment.Start) {
                    Text(stringResource(R.string.demo_title), color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(TouchGap)) {
                        Choice(stringResource(R.string.source_live), mode == SourceMode.LIVE) { mode = SourceMode.LIVE; apply(mode, scenario) }
                        Choice(stringResource(R.string.source_replay), mode == SourceMode.REPLAY) { mode = SourceMode.REPLAY; apply(mode, scenario) }
                        Choice(stringResource(R.string.source_hybrid), mode == SourceMode.HYBRID) { mode = SourceMode.HYBRID; apply(mode, scenario) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(TouchGap)) {
                        Choice(stringResource(R.string.scenario_demo), scenario == "demo_short.json") { scenario = "demo_short.json"; apply(mode, scenario) }
                        Choice(stringResource(R.string.scenario_commute), scenario == "commute.json") { scenario = "commute.json"; apply(mode, scenario) }
                    }
                    Choice(stringResource(R.string.demo_seed), seed) {
                        seed = !seed
                        val enabled = seed
                        lifecycleScope.launch { recorder.setDemoSeed(enabled) }
                    }
                    Spacer(Modifier.weight(1f))
                    CarButton(stringResource(R.string.restart), { recorder.restart() })
                }
            }
        }
    }

    private fun apply(mode: SourceMode, scenario: String) {
        settings.mode = mode
        settings.scenario = scenario
        recorder.restart()
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
        CarButton(label, onClick, style = if (selected) CarButtonStyle.ACTIVE else CarButtonStyle.SECONDARY)
}
