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
            nm.createNotificationChannel(NotificationChannel(CHANNEL_MOTION,"Motion Alerts",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)})
            nm.createNotificationChannel(NotificationChannel(CHANNEL_AI,"AI Detections",NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STREAM,"Live Stream",NotificationManager.IMPORTANCE_LOW))
        }
    }
    companion object {
        lateinit var instance: SecureCamApp private set
        const val CHANNEL_MOTION = "channel_motion"
        const val CHANNEL_AI = "channel_ai"
        const val CHANNEL_STREAM = "channel_stream"
    }
}