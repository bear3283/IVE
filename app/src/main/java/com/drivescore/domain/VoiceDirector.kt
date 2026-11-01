package com.drivescore.domain

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.drivescore.data.voice.VoiceOutput
import com.drivescore.data.voice.speechAttributes
import com.drivescore.scoring.DrivingSpeech
import com.drivescore.scoring.Gear
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceKind { BRIEFING, HAZARD, DEBRIEF, REPLAY, ANSWER }
enum class VoiceLevel { ALL, KEY, OFF }

/** 설정과 주행 상태에 따라 발화 여부를 정한다. */
object VoicePolicy {
    fun allows(kind: VoiceKind, level: VoiceLevel, gear: Gear?, text: String): Boolean {
        val byLevel = when (level) {
            VoiceLevel.OFF -> false
            VoiceLevel.KEY -> kind == VoiceKind.DEBRIEF || kind == VoiceKind.ANSWER || kind == VoiceKind.HAZARD
            VoiceLevel.ALL -> true
        }
        if (!byLevel) return false
        return if (kind == VoiceKind.HAZARD) gear != Gear.P && DrivingSpeech.allowed(text) else gear == Gear.P
    }
}

/** 모든 발화의 단일 통로(DESIGN 9절): 설정 필터 → 주행 중 규칙 → 오디오 포커스 → 발화. 새 발화가 이전 발화를 끊는다. */
@Singleton
class VoiceDirector @Inject constructor(
    @ApplicationContext context: Context,
    private val output: VoiceOutput,
    private val settings: RecorderSettings,
    gate: DriveGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val gear = gate.state
    private var current: Job? = null
    private var currentKind: VoiceKind? = null

    private val _caption = MutableStateFlow<String?>(null)
    /** 정차 중 화면에 함께 보여 주는 마지막 문장. 음성을 꺼도 답을 읽을 수 있다. */
    val caption: StateFlow<String?> = _caption.asStateFlow()

    init {
        // P를 벗어나면 정차용 발화를 즉시 끊고 자막을 지운다.
        gate.state.onEach {
            if (it.gear != Gear.P && it.gear != null) {
                _caption.value = null
                if (currentKind != VoiceKind.HAZARD) stop()
            }
        }.launchIn(scope)
    }

    /** @return 끝까지 말했으면 true. 끊기거나 걸러지면 false. */
    suspend fun speak(kind: VoiceKind, text: String): Boolean {
        val g = gear.value.gear
        if (g == Gear.P) _caption.value = text
        if (!VoicePolicy.allows(kind, settings.voiceLevel, g, text)) {
            Log.d(TAG, "걸러짐 $kind: $text")
            return false
        }
        stop()
        val job = scope.async { play(text) }
        current = job
        currentKind = kind
        return try {
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    fun clearCaption() { _caption.value = null }

    fun stop() {
        current?.cancel()
        current = null
        currentKind = null
    }

    private suspend fun play(text: String): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes())
            .build()
        // 다른 안내가 포커스를 쥐고 있으면 잠시 기다리고, 그래도 안 되면 말하지 않는다.
        var waited = 0L
        while (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (waited >= FOCUS_WAIT_MS) {
                Log.w(TAG, "오디오 포커스 실패, 발화 생략: $text")
                return false
            }
            delay(FOCUS_RETRY_MS)
            waited += FOCUS_RETRY_MS
        }
        return try {
            Log.d(TAG, "발화: $text")
            output.speak(text).also { Log.d(TAG, "발화 끝($it)") }
        } finally {
            audio.abandonAudioFocusRequest(request)
        }
    }

    private companion object {
        const val TAG = "VoiceDirector"
        const val FOCUS_RETRY_MS = 500L
        const val FOCUS_WAIT_MS = 5_000L
    }
}
