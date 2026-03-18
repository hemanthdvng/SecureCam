package com.securecam.camera
import android.app.Service; import android.content.Intent; import android.os.IBinder
import com.securecam.utils.NotificationHelper
class CameraStreamingService : Service() {
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int { startForeground(1, NotificationHelper.buildStreamingServiceNotification(this)); return START_STICKY }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}