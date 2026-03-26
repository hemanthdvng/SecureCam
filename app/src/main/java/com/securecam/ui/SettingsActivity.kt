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
        binding.switchMotionAlerts.isChecked = AppPreferences.motionAlertsEnabled
        binding.switchFaceDetection.isChecked = AppPreferences.faceDetectionEnabled
        binding.switchObjectDetection.isChecked = AppPreferences.objectDetectionEnabled
        binding.switchNightMode.isChecked = AppPreferences.nightModeEnabled
        binding.switchAutoNightMode.isChecked = AppPreferences.autoNightModeEnabled
        binding.switchAudioStream.isChecked = AppPreferences.audioStreamEnabled
        binding.switchFlashOnMotion.isChecked = AppPreferences.flashOnMotion
        binding.switchAutoRecord.isChecked = AppPreferences.autoRecordOnMotion
        binding.seekbarMotionSensitivity.progress = AppPreferences.motionSensitivity
        binding.tvMotionSensitivityValue.text = "${AppPreferences.motionSensitivity}%"
        binding.seekbarVideoQuality.progress = AppPreferences.videoQuality
        binding.tvVideoQualityValue.text = getQualityLabel(AppPreferences.videoQuality)
        binding.etCustomServerUrl.setText(AppPreferences.customSignalingServer)
    }

    private fun setupListeners() {
        binding.switchMotionAlerts.setOnCheckedChangeListener { _, checked ->
            AppPreferences.motionAlertsEnabled = checked
        }
        binding.switchFaceDetection.setOnCheckedChangeListener { _, checked ->
            AppPreferences.faceDetectionEnabled = checked
        }
        binding.switchObjectDetection.setOnCheckedChangeListener { _, checked ->
            AppPreferences.objectDetectionEnabled = checked
        }
        binding.switchNightMode.setOnCheckedChangeListener { _, checked ->
            AppPreferences.nightModeEnabled = checked
        }
        binding.switchAutoNightMode.setOnCheckedChangeListener { _, checked ->
            AppPreferences.autoNightModeEnabled = checked
            binding.switchNightMode.isEnabled = !checked
        }
        binding.switchAudioStream.setOnCheckedChangeListener { _, checked ->
            AppPreferences.audioStreamEnabled = checked
        }
        binding.switchFlashOnMotion.setOnCheckedChangeListener { _, checked ->
            AppPreferences.flashOnMotion = checked
        }

        binding.seekbarMotionSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(1, 100)
                binding.tvMotionSensitivityValue.text = "$clamped%"
                AppPreferences.motionSensitivity = clamped
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekbarVideoQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvVideoQualityValue.text = getQualityLabel(progress)
                AppPreferences.videoQuality = progress
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnSaveServer.setOnClickListener {
            AppPreferences.customSignalingServer = binding.etCustomServerUrl.text.toString().trim()
            binding.tvServerSaved.visibility = android.view.View.VISIBLE
            binding.tvServerSaved.postDelayed({
                binding.tvServerSaved.visibility = android.view.View.GONE
            }, 2000)
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun getQualityLabel(progress: Int): String = when {
        progress < 25 -> "Low (360p)"
        progress < 50 -> "Medium (480p)"
        progress < 75 -> "High (720p)"
        else -> "Ultra HD (1080p)"
    }
}
