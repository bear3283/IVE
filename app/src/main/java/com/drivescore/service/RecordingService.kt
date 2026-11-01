package com.drivescore.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.drivescore.R
import com.drivescore.domain.TripRecorder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 앱이 전경이 아니어도 기록이 이어지도록 하는 포그라운드 서비스. */
@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var recorder: TripRecorder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.recording_notice))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        recorder.restart()
        return START_STICKY
    }

    override fun onDestroy() {
        recorder.stop()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 1
    }
}
