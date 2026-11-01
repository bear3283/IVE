package com.drivescore.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

private const val TAG = "PleosVoice"
private const val INIT_TIMEOUT_MS = 5_000L
private const val START_TIMEOUT_MS = 8_000L
private const val SPEAK_TIMEOUT_MS = 60_000L

/** ON_DEVICE만 쓴다. Client ID/Secret과 네트워크가 필요 없다. */
class PleosVoiceOutput(private val context: Context) : VoiceOutput {
    private var tts: TextToSpeech? = null
    private var started: CompletableDeferred<Boolean>? = null
    private var finished: CompletableDeferred<Boolean>? = null

    private val listener = object : EventListener {
        override fun onStart() { started?.complete(true) }
        override fun onReady() = Unit
        override fun onDone() { finish(true) }
        override fun onStop() { finish(true) }
        override fun onError(type: TtsError) {
            Log.w(TAG, "TTS 오류: $type")
            started?.complete(false)
            finish(false)
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun finish(ok: Boolean) {
        started?.complete(ok)
        finished?.complete(ok)
    }

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val created = runCatching {
            TextToSpeech(context, TtsMode.ON_DEVICE).also {
                it.initialize(object : TtsInit {
                    override fun onSuccess() { ready.complete(true) }
                    override fun onFailure(type: TtsError) {
                        Log.w(TAG, "TTS 초기화 실패: $type")
                        ready.complete(false)
                    }
                })
            }
        }.onFailure { Log.w(TAG, "TTS 생성 실패", it) }.getOrNull() ?: return null
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return null
        return runCatching {
            created.addEventListener(listener)
            created.setLocale(Locale.KOREA)
            created.setAudioAttributes(speechAttributes())
            created
        }.onFailure { Log.w(TAG, "TTS 설정 실패", it) }.getOrNull()?.also { tts = it }
    }

    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.Main) {
        val engine = engine() ?: return@withContext false
        val s = CompletableDeferred<Boolean>().also { started = it }
        val f = CompletableDeferred<Boolean>().also { finished = it }
        try {
            engine.speak(text, true)
            if (withTimeoutOrNull(START_TIMEOUT_MS) { s.await() } != true) return@withContext false
            // 엔진 오류는 시작 신호 뒤에 올 수 있다(실측). 이때는 대체 엔진이 읽는다.
            withTimeoutOrNull(SPEAK_TIMEOUT_MS) { f.await() } ?: true
        } catch (e: CancellationException) {
            runCatching { engine.stop() }
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "TTS 호출 실패", e)
            false
        }
    }

    override fun stop() { runCatching { tts?.stop() } }
}

class PleosVoiceInput(private val context: Context) : VoiceInput {
    private var stt: SpeechToText? = null
    private var result: CompletableDeferred<Heard>? = null
    private var recording: CompletableDeferred<Unit>? = null
    private var partial = ""

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            partial = text
            if (isFinal) result?.complete(Heard.Text(text))
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() { recording?.complete(Unit) }
        override fun onEndedRecognition() { result?.complete(current()) }
        override fun onError(type: SttError) {
            Log.w(TAG, "STT 오류: $type")
            result?.complete(Heard.Unavailable)
        }
        override fun onReady() { recording?.complete(Unit) }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun current() = if (partial.isBlank()) Heard.Nothing else Heard.Text(partial)

    private suspend fun engine(): SpeechToText? {
        stt?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val created = runCatching {
            SpeechToText(context, SttMode.ON_DEVICE).also {
                it.initialize(object : SttInit {
                    override fun onSuccess() { ready.complete(true) }
                    override fun onFailure(type: SttError) {
                        Log.w(TAG, "STT 초기화 실패: $type")
                        ready.complete(false)
                    }
                })
            }
        }.onFailure { Log.w(TAG, "STT 생성 실패", it) }.getOrNull() ?: return null
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return null
        return runCatching {
            created.addListener(listener)
            created.setLocale(Locale.KOREA)
            created
        }.onFailure { Log.w(TAG, "STT 설정 실패", it) }.getOrNull()?.also { stt = it }
    }

    override suspend fun listen(timeoutMs: Long): Heard = withContext(Dispatchers.Main) {
        val engine = engine() ?: return@withContext Heard.Unavailable
        partial = ""
        val r = CompletableDeferred<Heard>().also { result = it }
        try {
            val rec = CompletableDeferred<Unit>().also { recording = it }
            engine.request()
            // 엔진이 마이크를 여는 데 2초 넘게 걸린다(실측). 듣는 시간은 그다음부터 잰다.
            withTimeoutOrNull(START_TIMEOUT_MS) { rec.await() }
            val heard = withTimeoutOrNull(timeoutMs) { r.await() }
            if (heard == null) runCatching { engine.stop() }
            heard ?: current()
        } catch (e: CancellationException) {
            runCatching { engine.stop() }
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "STT 호출 실패", e)
            Heard.Unavailable
        }
    }

    override fun stop() { runCatching { stt?.stop() } }
}

/** 내비 안내보다 후순위인 음성 안내. */
internal fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
