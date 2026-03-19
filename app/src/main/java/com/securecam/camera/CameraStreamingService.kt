package com.securecam.camera

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.securecam.utils.NotificationHelper

class CameraStreamingService : Service() {

    private val TAG = "CameraStreamingService"
    private val NOTIF_ID = 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CameraStreamingService started")
        val notification = NotificationHelper.buildStreamingServiceNotification(this)
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "CameraStreamingService stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
