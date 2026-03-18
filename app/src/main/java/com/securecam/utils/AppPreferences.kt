package com.securecam.utils
import android.content.Context
import android.content.SharedPreferences
object AppPreferences {
    private lateinit var prefs: SharedPreferences
    fun init(context: Context) { prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE) }
    var motionAlertsEnabled: Boolean get() = prefs.getBoolean("motion_alerts",true) set(v) = prefs.edit().putBoolean("motion_alerts",v).apply()
    var faceDetectionEnabled: Boolean get() = prefs.getBoolean("face_detection",true) set(v) = prefs.edit().putBoolean("face_detection",v).apply()
    var objectDetectionEnabled: Boolean get() = prefs.getBoolean("object_detection",true) set(v) = prefs.edit().putBoolean("object_detection",v).apply()
    var nightModeEnabled: Boolean get() = prefs.getBoolean("night_mode",false) set(v) = prefs.edit().putBoolean("night_mode",v).apply()
    var autoNightModeEnabled: Boolean get() = prefs.getBoolean("auto_night_mode",true) set(v) = prefs.edit().putBoolean("auto_night_mode",v).apply()
    var audioStreamEnabled: Boolean get() = prefs.getBoolean("audio_stream",true) set(v) = prefs.edit().putBoolean("audio_stream",v).apply()
    var flashOnMotion: Boolean get() = prefs.getBoolean("flash_on_motion",false) set(v) = prefs.edit().putBoolean("flash_on_motion",v).apply()
    var motionSensitivity: Int get() = prefs.getInt("motion_sensitivity",40) set(v) = prefs.edit().putInt("motion_sensitivity",v).apply()
    var videoQuality: Int get() = prefs.getInt("video_quality",50) set(v) = prefs.edit().putInt("video_quality",v).apply()
    var lastRoomCode: String get() = prefs.getString("last_room_code","") ?: "" set(v) = prefs.edit().putString("last_room_code",v).apply()
    var customSignalingServer: String get() = prefs.getString("custom_signaling","") ?: "" set(v) = prefs.edit().putString("custom_signaling",v).apply()
    var useBackCamera: Boolean get() = prefs.getBoolean("use_back_camera",true) set(v) = prefs.edit().putBoolean("use_back_camera",v).apply()
}