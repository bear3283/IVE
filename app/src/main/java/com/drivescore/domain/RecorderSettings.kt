package com.drivescore.domain

import android.content.Context
import com.drivescore.data.signal.SourceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecorderSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: SourceMode
        get() = runCatching { SourceMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(SourceMode.HYBRID)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    var scenario: String
        get() = prefs.getString("scenario", null) ?: "demo_short.json"
        set(v) = prefs.edit().putString("scenario", v).apply()

    var voiceLevel: VoiceLevel
        get() = runCatching { VoiceLevel.valueOf(prefs.getString("voiceLevel", null) ?: "") }.getOrDefault(VoiceLevel.ALL)
        set(v) = prefs.edit().putString("voiceLevel", v.name).apply()
}
