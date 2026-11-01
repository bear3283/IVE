package com.drivescore.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivescore.domain.DriveGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 주행 중 표지. 점수와 애니메이션 없이 기록 중임만 알린다. 멈추면 스스로 닫힌다. */
@AndroidEntryPoint
class DrivingCoverActivity : ComponentActivity() {
    @Inject lateinit var gate: DriveGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RecordingCover() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gate.state.first { it.canShowContent }
                finish()
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
        }
    }
}
