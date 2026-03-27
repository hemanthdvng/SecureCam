package com.securecam.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

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

    var intruderModeEnabled: Boolean
        get() = prefs.getBoolean("intruder_mode", true)
        set(value) = prefs.edit().putBoolean("intruder_mode", value).apply()

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

    var autoRecordOnMotion: Boolean
        get() = prefs.getBoolean("auto_record_motion", false)
        set(value) = prefs.edit().putBoolean("auto_record_motion", value).apply()

    var alarmOnIntruderEnabled: Boolean
        get() = prefs.getBoolean("alarm_on_intruder", true)
        set(value) = prefs.edit().putBoolean("alarm_on_intruder", value).apply()

    var detectPersons: Boolean
        get() = prefs.getBoolean("detect_persons", true)
        set(value) = prefs.edit().putBoolean("detect_persons", value).apply()

    var detectVehicles: Boolean
        get() = prefs.getBoolean("detect_vehicles", true)
        set(value) = prefs.edit().putBoolean("detect_vehicles", value).apply()

    var detectPackages: Boolean
        get() = prefs.getBoolean("detect_packages", true)
        set(value) = prefs.edit().putBoolean("detect_packages", value).apply()

    var detectAnimals: Boolean
        get() = prefs.getBoolean("detect_animals", false)
        set(value) = prefs.edit().putBoolean("detect_animals", value).apply()

    var maxRecordingDurationSec: Int
        get() = prefs.getInt("max_rec_duration_sec", 15)
        set(value) = prefs.edit().putInt("max_rec_duration_sec", value).apply()

    var motionGracePeriodSec: Int
        get() = prefs.getInt("motion_grace_sec", 10)
        set(value) = prefs.edit().putInt("motion_grace_sec", value).apply()

    var recordingSaveLocation: Int
        get() = prefs.getInt("recording_location", 0)
        set(value) = prefs.edit().putInt("recording_location", value).apply()

    var recordingCustomPath: String
        get() = prefs.getString("recording_custom_path", "") ?: ""
        set(value) = prefs.edit().putString("recording_custom_path", value).apply()

    var eventRetentionDays: Int
        get() = prefs.getInt("event_retention_days", 7)
        set(value) = prefs.edit().putInt("event_retention_days", value).apply()

    /**
     * Face recognition model type:
     * 0 = MobileFaceNet (128-dim, included in assets/, faster)
     * 1 = ArcFace MobileNet (512-dim, must download or add to assets/, more accurate)
     */
    var faceModelType: Int
        get() = prefs.getInt("face_model_type", 0)
        set(value) = prefs.edit().putInt("face_model_type", value).apply()

    fun getRecordingDirectory(): File {
        return when (recordingSaveLocation) {
            1    -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "SecureCam")
            2    -> if (recordingCustomPath.isNotEmpty()) File(recordingCustomPath)
                    else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SecureCam")
            else -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SecureCam")
        }
    }
}
