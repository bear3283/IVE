package com.drivescore.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private const val TAG = "AndroidVoice"

class AndroidVoiceOutput(private val context: Context) : VoiceOutput {
    private var tts: TextToSpeech? = null
    private var done: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val created = TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) }
        if (withTimeoutOrNull(5_000) { ready.await() } != true) {
            Log.w(TAG, "TTS 엔진 초기화 실패, 기본 엔진: ${created.defaultEngine}")
            return null
        }
        val language = created.setLanguage(Locale.KOREA)
        if (language < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "한국어 음성 없음: $language")
            created.shutdown()
            return null
        }
        created.setAudioAttributes(speechAttributes())
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { done?.complete(true) }
            @Deprecated("API 21에서 대체됨") override fun onError(id: String?) { done?.complete(false) }
            override fun onError(id: String?, code: Int) {
                Log.w(TAG, "발화 오류: $code")
                done?.complete(false)
            }
            override fun onStop(id: String?, interrupted: Boolean) { done?.complete(true) }
        })
        tts = created
        return created
    }

    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.Main) {
        val engine = engine() ?: return@withContext false
        val d = CompletableDeferred<Boolean>().also { done = it }
        try {
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ivecore") != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak 거부")
                return@withContext false
            }
            withTimeoutOrNull(60_000) { d.await() } ?: false
        } catch (e: CancellationException) {
            engine.stop()
            throw e
        }
    }

    override fun stop() { tts?.stop() }
}

class AndroidVoiceInput(private val context: Context) : VoiceInput {
    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(timeoutMs: Long): Heard = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext Heard.Unavailable
        val r = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        val result = CompletableDeferred<Heard>()
        var partial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                result.complete(if (text.isBlank()) Heard.Nothing else Heard.Text(text))
            }
            override fun onPartialResults(b: Bundle?) {
                partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onError(code: Int) {
                Log.w(TAG, "인식 오류: $code")
                val silence = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                result.complete(if (silence) Heard.Nothing else Heard.Unavailable)
            }
            override fun onReadyForSpeech(b: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(v: Float) = Unit
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(t: Int, b: Bundle?) = Unit
        })
        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
            withTimeoutOrNull(timeoutMs) { result.await() } ?: if (partial.isBlank()) Heard.Nothing else Heard.Text(partial)
        } finally {
            r.destroy()
            recognizer = null
        }
    }

    override fun stop() { recognizer?.stopListening() }
}
