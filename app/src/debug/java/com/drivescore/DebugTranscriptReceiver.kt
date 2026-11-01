package com.drivescore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drivescore.domain.VoiceCoach
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** adb shell am broadcast -n com.drivescore/.DebugTranscriptReceiver --es text "왜 감점됐어" */
class DebugTranscriptReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun coach(): VoiceCoach }

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        EntryPointAccessors.fromApplication(context, Deps::class.java).coach().onTranscript(text)
    }
}
