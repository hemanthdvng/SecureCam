package com.securecam.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.securecam.R

object NotificationHelper {
    private const val CH_MOTION   = "motion_alerts"
    private const val CH_AI       = "ai_alerts"
    private const val CH_STREAM   = "stream_service"
    private const val CH_SECURITY = "security_alerts"

    private var notifId = 100

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MOTION,   "Motion Alerts",   NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_AI,       "AI Detections",   NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_STREAM,   "Streaming",       NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SECURITY, "Security Alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun showMotionAlert(context: Context, score: Float) {
        val pct = "%.0f".format(score * 100)
        notify(context, CH_MOTION, "⚠️ Motion Detected", "Motion intensity: $pct%")
    }

    fun showObjectDetectionAlert(context: Context, label: String, confidence: Float) {
        val pct = "%.0f".format(confidence * 100)
        notify(context, CH_AI, "🤖 $label detected", "Confidence: $pct%")
    }

    fun showFaceAlert(context: Context, count: Int) {
        notify(context, CH_AI, "👤 Face Detected", "$count face(s) in view")
    }

    fun showUnknownFaceAlert(context: Context) {
        notify(context, CH_SECURITY, "❓ Unknown Person!", "Unrecognised face detected by camera")
    }

    fun showFaceRecognisedAlert(context: Context, name: String) {
        notify(context, CH_AI, "✅ $name identified", "Known person recognised by camera")
    }

    fun buildStreamingServiceNotification(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CH_STREAM)
            .setContentTitle("SecureCam Active")
            .setContentText("Camera streaming in progress")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun notify(context: Context, channel: String, title: String, text: String) {
        createChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        nm.notify(notifId++, n)
    }
}
