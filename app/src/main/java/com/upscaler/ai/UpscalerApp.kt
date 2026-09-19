package com.upscaler.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class UpscalerApp : Application() {
    companion object { const val CHANNEL_PROGRESS = "upscale_progress" }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }
}
