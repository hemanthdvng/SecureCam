package com.securecam.utils

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.securecam.R
import com.securecam.SecureCamApp

object NotificationHelper {

    private var notificationId = 1000

    fun showMotionAlert(context: Context, score: Float) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val percent = (score * 100).toInt()

        val notification = NotificationCompat.Builder(context, SecureCamApp.CHANNEL_MOTION)
            .setSmallIcon(R.drawable.ic_motion)
            .setContentTitle("⚠️ Motion Detected!")
            .setContentText("Motion intensity: $percent% — Tap to view live feed")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Motion detected in the monitored area. Intensity: $percent%\nTap to open the live feed.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setOnlyAlertOnce(false)
            .build()

        nm.notify(notificationId++, notification)
        vibrateDevice(context)
    }

    fun showObjectDetectionAlert(context: Context, label: String, confidence: Float) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val percent = (confidence * 100).toInt()

        val emoji = getObjectEmoji(label)
        val notification = NotificationCompat.Builder(context, SecureCamApp.CHANNEL_AI)
            .setSmallIcon(R.drawable.ic_ai)
            .setContentTitle("$emoji AI Detection: $label")
            .setContentText("Detected with $percent% confidence")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        nm.notify(notificationId++, notification)
    }

    fun showFaceAlert(context: Context, faceCount: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, SecureCamApp.CHANNEL_AI)
            .setSmallIcon(R.drawable.ic_face)
            .setContentTitle("👤 ${if (faceCount == 1) "Person" else "$faceCount People"} Detected")
            .setContentText("Face detected in monitored area")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200))
            .build()

        nm.notify(notificationId++, notification)
    }

    fun buildStreamingServiceNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, SecureCamApp.CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_camera_stream)
            .setContentTitle("SecureCam is streaming")
            .setContentText("Camera stream is live • AI monitoring active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun vibrateDevice(context: Context) {
        if (!AppPreferences.motionAlertsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm.defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 300, 100, 300), -1)
            }
        }
    }

    private fun getObjectEmoji(label: String): String = when (label.lowercase()) {
        "person", "human" -> "🧍"
        "car", "vehicle" -> "🚗"
        "animal", "dog", "cat" -> "🐾"
        "bicycle", "bike" -> "🚲"
        "backpack", "bag" -> "🎒"
        "phone", "cell phone" -> "📱"
        "laptop", "computer" -> "💻"
        else -> "📍"
    }
}
