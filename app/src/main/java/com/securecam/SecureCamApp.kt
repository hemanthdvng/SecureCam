package com.securecam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.securecam.utils.AppPreferences

class SecureCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppPreferences.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Motion Detection Channel
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MOTION, "Motion Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when motion is detected"
                    enableVibration(true)
                    enableLights(true)
                }
            )

            // AI Detection Channel
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_AI, "AI Detections",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Alerts for AI object and face detection"
                }
            )

            // Streaming Service Channel
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STREAM, "Live Stream",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Ongoing camera stream notification"
                }
            )
        }
    }

    companion object {
        lateinit var instance: SecureCamApp
            private set

        const val CHANNEL_MOTION = "channel_motion"
        const val CHANNEL_AI = "channel_ai"
        const val CHANNEL_STREAM = "channel_stream"
    }
}
