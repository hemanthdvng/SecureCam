package com.securecam.ui

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.securecam.databinding.ActivitySettingsBinding
import com.securecam.utils.AppPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.switchMotionAlerts.isChecked    = AppPreferences.motionAlertsEnabled
        binding.switchFaceDetection.isChecked   = AppPreferences.faceDetectionEnabled
        binding.switchObjectDetection.isChecked = AppPreferences.objectDetectionEnabled
        binding.switchNightMode.isChecked       = AppPreferences.nightModeEnabled
        binding.switchAutoNightMode.isChecked   = AppPreferences.autoNightModeEnabled
        binding.switchAudioStream.isChecked     = AppPreferences.audioStreamEnabled
        binding.switchFlashOnMotion.isChecked   = AppPreferences.flashOnMotion
        binding.switchAutoRecord.isChecked      = AppPreferences.autoRecordOnMotion
        binding.switchIntruderMode.isChecked    = AppPreferences.intruderModeEnabled
        binding.seekbarMotionSensitivity.progress = AppPreferences.motionSensitivity
        binding.tvMotionSensitivityValue.text     = "${AppPreferences.motionSensitivity}%"
        binding.seekbarVideoQuality.progress      = AppPreferences.videoQuality
        binding.tvVideoQualityValue.text          = getQualityLabel(AppPreferences.videoQuality)
        binding.etCustomServerUrl.setText(AppPreferences.customSignalingServer)
    }

    private fun setupListeners() {
        binding.switchMotionAlerts.setOnCheckedChangeListener    { _, v -> AppPreferences.motionAlertsEnabled    = v }
        binding.switchFaceDetection.setOnCheckedChangeListener   { _, v -> AppPreferences.faceDetectionEnabled   = v }
        binding.switchObjectDetection.setOnCheckedChangeListener { _, v -> AppPreferences.objectDetectionEnabled = v }
        binding.switchNightMode.setOnCheckedChangeListener       { _, v -> AppPreferences.nightModeEnabled       = v }
        binding.switchAutoNightMode.setOnCheckedChangeListener   { _, v ->
            AppPreferences.autoNightModeEnabled = v
            binding.switchNightMode.isEnabled   = !v
        }
        binding.switchAudioStream.setOnCheckedChangeListener     { _, v -> AppPreferences.audioStreamEnabled     = v }
        binding.switchFlashOnMotion.setOnCheckedChangeListener   { _, v -> AppPreferences.flashOnMotion          = v }
        binding.switchAutoRecord.setOnCheckedChangeListener      { _, v -> AppPreferences.autoRecordOnMotion     = v }
        binding.switchIntruderMode.setOnCheckedChangeListener    { _, v -> AppPreferences.intruderModeEnabled    = v }

        binding.seekbarMotionSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = p.coerceIn(1, 100)
                binding.tvMotionSensitivityValue.text = "$v%"
                AppPreferences.motionSensitivity = v
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekbarVideoQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                binding.tvVideoQualityValue.text = getQualityLabel(p)
                AppPreferences.videoQuality = p
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnSaveServer.setOnClickListener {
            AppPreferences.customSignalingServer = binding.etCustomServerUrl.text.toString().trim()
            binding.tvServerSaved.visibility = android.view.View.VISIBLE
            binding.tvServerSaved.postDelayed({ binding.tvServerSaved.visibility = android.view.View.GONE }, 2000)
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun getQualityLabel(p: Int): String = when {
        p < 25 -> "Low (360p)"; p < 50 -> "Medium (480p)"; p < 75 -> "High (720p)"; else -> "Ultra HD (1080p)"
    }
}
