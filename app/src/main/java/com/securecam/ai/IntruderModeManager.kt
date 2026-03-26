package com.securecam.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.securecam.utils.NotificationHelper

/**
 * Handles the response when an unknown face is detected (Intruder Mode):
 *   - Plays looping alarm sound
 *   - Blinks flashlight via torchCallback (avoids CameraX conflicts)
 *   - Fires repeated high-priority notifications
 */
class IntruderModeManager(
    private val context: Context,
    private val torchCallback: (Boolean) -> Unit
) {
    private val TAG = "IntruderMode"
    private var isActive = false
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var flashOn = false
    private var flashRunnable: Runnable? = null

    fun activate() {
        if (isActive) return
        isActive = true
        Log.d(TAG, "Intruder mode ACTIVATED")
        startAlarmSound()
        startFlashBlink()
        NotificationHelper.showIntruderAlert(context)
        var count = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isActive || count >= 3) return
                count++
                NotificationHelper.showIntruderAlert(context)
                handler.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    fun deactivate() {
        if (!isActive) return
        isActive = false
        Log.d(TAG, "Intruder mode DEACTIVATED")
        stopAlarmSound()
        stopFlashBlink()
        handler.removeCallbacksAndMessages(null)
    }

    fun isActive() = isActive

    private fun startAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Alarm started")
        } catch (e: Exception) {
            Log.e(TAG, "Alarm error: ${e.message}")
        }
    }

    private fun stopAlarmSound() {
        try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null }
        catch (e: Exception) { Log.e(TAG, "Stop alarm: ${e.message}") }
    }

    private fun startFlashBlink() {
        val r = object : Runnable {
            override fun run() {
                if (!isActive) { torchCallback(false); return }
                flashOn = !flashOn
                torchCallback(flashOn)
                handler.postDelayed(this, 350)
            }
        }
        flashRunnable = r
        handler.post(r)
    }

    private fun stopFlashBlink() {
        flashRunnable?.let { handler.removeCallbacks(it) }
        torchCallback(false)
    }

    fun release() { deactivate() }
}
