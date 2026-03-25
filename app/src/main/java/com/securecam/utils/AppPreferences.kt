package com.securecam.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment

object AppPreferences {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    }

    var motionAlertsEnabled: Boolean
        get() = prefs.getBoolean("motion_alerts", true)
        set(value) = prefs.edit().putBoolean("motion_alerts", value).apply()

    var faceDetectionEnabled: Boolean
        get() = prefs.getBoolean("face_detection", true)
        set(value) = prefs.edit().putBoolean("face_detection", value).apply()

    var faceRecognitionEnabled: Boolean
        get() = prefs.getBoolean("face_recognition", true)
        set(value) = prefs.edit().putBoolean("face_recognition", value).apply()

    var objectDetectionEnabled: Boolean
        get() = prefs.getBoolean("object_detection", true)
        set(value) = prefs.edit().putBoolean("object_detection", value).apply()

    var nightModeEnabled: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(value) = prefs.edit().putBoolean("night_mode", value).apply()

    var autoNightModeEnabled: Boolean
        get() = prefs.getBoolean("auto_night_mode", true)
        set(value) = prefs.edit().putBoolean("auto_night_mode", value).apply()

    var audioStreamEnabled: Boolean
        get() = prefs.getBoolean("audio_stream", true)
        set(value) = prefs.edit().putBoolean("audio_stream", value).apply()

    var flashOnMotion: Boolean
        get() = prefs.getBoolean("flash_on_motion", false)
        set(value) = prefs.edit().putBoolean("flash_on_motion", value).apply()

    var motionSensitivity: Int
        get() = prefs.getInt("motion_sensitivity", 40)
        set(value) = prefs.edit().putInt("motion_sensitivity", value).apply()

    var videoQuality: Int
        get() = prefs.getInt("video_quality", 80)
        set(value) = prefs.edit().putInt("video_quality", value).apply()

    var lastRoomCode: String
        get() = prefs.getString("last_room_code", "") ?: ""
        set(value) = prefs.edit().putString("last_room_code", value).apply()

    var customSignalingServer: String
        get() = prefs.getString("custom_signaling", "") ?: ""
        set(value) = prefs.edit().putString("custom_signaling", value).apply()

    var useBackCamera: Boolean
        get() = prefs.getBoolean("use_back_camera", true)
        set(value) = prefs.edit().putBoolean("use_back_camera", value).apply()

    // Auto-recording
    var autoRecordOnMotion: Boolean
        get() = prefs.getBoolean("auto_record_motion", false)
        set(value) = prefs.edit().putBoolean("auto_record_motion", value).apply()

    // Recording save location: 0=Movies/SecureCam, 1=DCIM/SecureCam, 2=Custom
    var recordingSaveLocation: Int
        get() = prefs.getInt("recording_location", 0)
        set(value) = prefs.edit().putInt("recording_location", value).apply()

    var recordingCustomPath: String
        get() = prefs.getString("recording_custom_path", "") ?: ""
        set(value) = prefs.edit().putString("recording_custom_path", value).apply()

    fun getRecordingDirectory(): java.io.File {
        return when (recordingSaveLocation) {
            1 -> java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "SecureCam")
            2 -> if (recordingCustomPath.isNotEmpty()) java.io.File(recordingCustomPath) else
                 java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SecureCam")
            else -> java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SecureCam")
        }
    }
}
