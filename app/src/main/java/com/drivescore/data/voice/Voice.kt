package com.drivescore.data.voice

/** 발화 한 건을 끝까지 기다린다. 기기에서만 동작하는 엔진을 쓴다. */
interface VoiceOutput {
    /** @return 끝까지 말했으면 true. 시작도 못 하면 false여서 대체 엔진으로 넘어간다. */
    suspend fun speak(text: String): Boolean
    fun stop()
}

sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Nothing : Heard
    /** 엔진을 쓸 수 없다. 대체 엔진으로 넘어간다. */
    data object Unavailable : Heard
}

interface VoiceInput {
    suspend fun listen(timeoutMs: Long): Heard
    fun stop()
}

/** Pleos가 막혀 있으면 Android 기본 엔진을 쓴다. 한 번 실패한 엔진은 다시 시도하지 않는다. */
class FallbackVoiceOutput(private val primary: VoiceOutput, private val secondary: VoiceOutput) : VoiceOutput {
    private var usePrimary = true

    override suspend fun speak(text: String): Boolean {
        if (usePrimary && primary.speak(text)) return true
        usePrimary = false
        return secondary.speak(text)
    }

    override fun stop() = if (usePrimary) primary.stop() else secondary.stop()
}

class FallbackVoiceInput(private val primary: VoiceInput, private val secondary: VoiceInput) : VoiceInput {
    private var usePrimary = true

    override suspend fun listen(timeoutMs: Long): Heard {
        if (usePrimary) {
            val heard = primary.listen(timeoutMs)
            if (heard != Heard.Unavailable) return heard
            usePrimary = false
        }
        return secondary.listen(timeoutMs)
    }

    override fun stop() = if (usePrimary) primary.stop() else secondary.stop()
}
