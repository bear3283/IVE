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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.drivescore.domain.loadTripDetails
import com.drivescore.service.RecordingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private sealed interface Screen {
    data object Home : Screen
    data class Replay(val tripId: Long) : Screen
    data class Result(val tripId: Long) : Screen
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var recorder: TripRecorder
    @Inject lateinit var settings: RecorderSettings
    @Inject lateinit var dao: DriveDao
    @Inject lateinit var gate: DriveGate

    private val screenState = mutableStateOf<Screen>(Screen.Home)
    private var arrivedTripId: Long? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = buildList {
            add("android.car.permission.CAR_SPEED")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(needed.toTypedArray())
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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

        LaunchedEffect(session, lastTripId) {
            val id = lastTripId
            if (session == Session.ARRIVED && id != null && id != arrivedTripId) {
                arrivedTripId = id
                screen = Screen.Replay(id)
            }
        }
        val close = {
            recorder.dismissResult()
            screen = Screen.Home
        }
        BackHandler(screen != Screen.Home, close)

        when (val s = screen) {
            Screen.Home -> Home { screen = Screen.Result(it) }
            is Screen.Replay -> WithDetails(s.tripId, close) { d ->
                // 궤적이 없으면 볼 것이 없으니 결과로 넘어간다.
                if (d.track.size < 2 || d.trip.durationSec <= 0) LaunchedEffect(Unit) { screen = Screen.Result(s.tripId) }
                else ReplayScreen(d) { screen = Screen.Result(s.tripId) }
            }
            is Screen.Result -> WithDetails(s.tripId, close) { d ->
                ResultScreen(d, onReplay = { screen = Screen.Replay(s.tripId) }, onClose = close)
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
    private fun Home(onOpenTrip: (Long) -> Unit) {
        val session by recorder.session.collectAsStateWithLifecycle()
        val log by recorder.log.collectAsStateWithLifecycle()
        val profileId by recorder.profileId.collectAsStateWithLifecycle()
        val trips by remember(profileId) {
            profileId?.let(dao::recentTrips) ?: emptyFlow()
        }.collectAsStateWithLifecycle(emptyList())
        var mode by remember { mutableStateOf(settings.mode) }
        var scenario by remember { mutableStateOf(settings.scenario) }
        var seed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { seed = recorder.isDemoSeed() }

        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.session_state, session.name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.source_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Choice(stringResource(R.string.source_live), mode == SourceMode.LIVE) { mode = SourceMode.LIVE; apply(mode, scenario) }
                        Choice(stringResource(R.string.source_replay), mode == SourceMode.REPLAY) { mode = SourceMode.REPLAY; apply(mode, scenario) }
                        Choice(stringResource(R.string.source_hybrid), mode == SourceMode.HYBRID) { mode = SourceMode.HYBRID; apply(mode, scenario) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Choice(stringResource(R.string.scenario_demo), scenario == "demo_short.json") { scenario = "demo_short.json"; apply(mode, scenario) }
                        Choice(stringResource(R.string.scenario_commute), scenario == "commute.json") { scenario = "commute.json"; apply(mode, scenario) }
                    }
                    Choice(stringResource(R.string.demo_seed), seed) {
                        seed = !seed
                        val enabled = seed
                        lifecycleScope.launch { recorder.setDemoSeed(enabled) }
                    }
                    Choice(stringResource(R.string.restart), false) { recorder.restart() }
                    Text(stringResource(R.string.limits_notice), style = MaterialTheme.typography.bodyLarge)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.trips_title), style = MaterialTheme.typography.titleLarge) }
                    items(trips, key = Trip::id) { t ->
                        TextButton({ onOpenTrip(t.id) }, Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
                            Text(stringResource(R.string.trip_line, t.id, t.total, stringResource(gradeLabel(t.grade)), t.distanceM / 1000), fontSize = 26.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    item { Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleLarge) }
                    items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodyLarge) }
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
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        val modifier = Modifier.heightIn(min = 76.dp)
        if (selected) Button(onClick, modifier) { Text(label) } else OutlinedButton(onClick, modifier) { Text(label) }
    }
}
