package com.securecam.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.securecam.camera.CameraStreamingService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed — SecureCam initialised")
            // If there's a saved room code the user had active, we could
            // auto-start the streaming service here (foreground only)
            AppPreferences.init(context)
            val lastRoom = AppPreferences.lastRoomCode
            if (lastRoom.isNotEmpty()) {
                Log.d("BootReceiver", "Last room was $lastRoom — service ready on demand")
            }
        }
    }
}
