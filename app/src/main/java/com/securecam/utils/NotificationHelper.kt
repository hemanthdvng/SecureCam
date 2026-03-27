package com.securecam.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CH_MOTION   = "motion_alerts"
    private const val CH_AI       = "ai_alerts"
    private const val CH_STREAM   = "stream_service"
    private const val CH_SECURITY = "security_alerts"
    private const val CH_INTRUDER = "intruder_alerts"
    private const val CH_OBJECT   = "object_alerts"

    private var notifId = 100

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(NotificationChannel(CH_MOTION,   "Motion Alerts",   NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_AI,       "AI Detections",   NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_STREAM,   "Streaming",       NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SECURITY, "Security Alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_OBJECT,   "Object Alerts",   NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(
            NotificationChannel(CH_INTRUDER, "🚨 Intruder Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                enableLights(true)
                lightColor = 0xFFFF0000.toInt()
            }
        )
    }

    fun showMotionAlert(context: Context, score: Float) {
        val pct = "%.0f".format(score * 100)
        notify(context, CH_MOTION, "⚠️ Motion Detected", "Intensity: $pct%", NotificationCompat.PRIORITY_HIGH)
    }

    /**
     * @param group   person | vehicle | package | animal | object
     * @param priority normal | warning | critical
     * @param reappeared true when the same class re-enters after >30 s absence
     */
    fun showObjectDetectionAlert(
        context: Context,
        label: String, group: String,
        confidence: Float, priority: String,
        reappeared: Boolean = false
    ) {
        val pct  = "%.0f".format(confidence * 100)
        val icon = groupIcon(group)
        val reStr = if (reappeared) " (reappeared)" else ""
        val title = when (priority) {
            "critical" -> "🚨 $icon $label detected$reStr"
            "warning"  -> "⚠️ $icon $label detected$reStr"
            else       -> "$icon $label detected$reStr"
        }
        val np = when (priority) {
            "critical" -> NotificationCompat.PRIORITY_MAX
            "warning"  -> NotificationCompat.PRIORITY_HIGH
            else       -> NotificationCompat.PRIORITY_DEFAULT
        }
        val ch = if (priority == "critical") CH_SECURITY else CH_OBJECT
        notify(context, ch, title, "Confidence: $pct%", np)
    }

    fun showFaceAlert(context: Context, count: Int) =
        notify(context, CH_AI, "👤 Face Detected", "$count face(s) in view")

    fun showUnknownFaceAlert(context: Context) =
        notify(context, CH_SECURITY, "❓ Unknown Person!", "Unrecognised face detected",
               NotificationCompat.PRIORITY_HIGH)

    fun showIntruderAlert(context: Context) =
        notify(context, CH_INTRUDER, "🚨 INTRUDER DETECTED!",
               "Unknown person — immediate attention required",
               NotificationCompat.PRIORITY_MAX)

    fun showFaceRecognisedAlert(context: Context, name: String) =
        notify(context, CH_AI, "✅ $name identified", "Known person recognised")

    fun buildStreamingServiceNotification(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CH_STREAM)
            .setContentTitle("SecureCam Active")
            .setContentText("Camera streaming in progress")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun groupIcon(group: String) = when (group) {
        "person"  -> "🧍"
        "vehicle" -> "🚗"
        "package" -> "📦"
        "animal"  -> "🐾"
        else      -> "🔍"
    }

    private fun notify(
        context: Context, channel: String, title: String, text: String,
        priority: Int = NotificationCompat.PRIORITY_HIGH
    ) {
        createChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()
        nm.notify(notifId++, n)
    }
}
