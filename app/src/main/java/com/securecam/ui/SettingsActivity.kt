package com.securecam.ui
import android.os.Bundle; import android.view.View; import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity; import com.securecam.databinding.ActivitySettingsBinding; import com.securecam.utils.AppPreferences
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    override fun onCreate(s: Bundle?) {
        super.onCreate(s); binding = ActivitySettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.switchMotionAlerts.isChecked = AppPreferences.motionAlertsEnabled
        binding.switchFaceDetection.isChecked = AppPreferences.faceDetectionEnabled
        binding.switchObjectDetection.isChecked = AppPreferences.objectDetectionEnabled
        binding.switchNightMode.isChecked = AppPreferences.nightModeEnabled
        binding.switchAutoNightMode.isChecked = AppPreferences.autoNightModeEnabled
        binding.switchAudioStream.isChecked = AppPreferences.audioStreamEnabled
        binding.switchFlashOnMotion.isChecked = AppPreferences.flashOnMotion
        binding.seekbarMotionSensitivity.progress = AppPreferences.motionSensitivity
        binding.tvMotionSensitivityValue.text = "${AppPreferences.motionSensitivity}%"
        binding.seekbarVideoQuality.progress = AppPreferences.videoQuality
        binding.tvVideoQualityValue.text = qual(AppPreferences.videoQuality)
        binding.switchMotionAlerts.setOnCheckedChangeListener{_,c->AppPreferences.motionAlertsEnabled=c}
        binding.switchFaceDetection.setOnCheckedChangeListener{_,c->AppPreferences.faceDetectionEnabled=c}
        binding.switchObjectDetection.setOnCheckedChangeListener{_,c->AppPreferences.objectDetectionEnabled=c}
        binding.switchNightMode.setOnCheckedChangeListener{_,c->AppPreferences.nightModeEnabled=c}
        binding.switchAutoNightMode.setOnCheckedChangeListener{_,c->AppPreferences.autoNightModeEnabled=c}
        binding.switchAudioStream.setOnCheckedChangeListener{_,c->AppPreferences.audioStreamEnabled=c}
        binding.switchFlashOnMotion.setOnCheckedChangeListener{_,c->AppPreferences.flashOnMotion=c}
        binding.seekbarMotionSensitivity.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb:SeekBar,p:Int,f:Boolean){binding.tvMotionSensitivityValue.text="$p%";AppPreferences.motionSensitivity=p}
            override fun onStartTrackingTouch(sb:SeekBar){}; override fun onStopTrackingTouch(sb:SeekBar){}})
        binding.seekbarVideoQuality.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb:SeekBar,p:Int,f:Boolean){binding.tvVideoQualityValue.text=qual(p);AppPreferences.videoQuality=p}
            override fun onStartTrackingTouch(sb:SeekBar){}; override fun onStopTrackingTouch(sb:SeekBar){}})
        binding.btnSaveServer.setOnClickListener{AppPreferences.customSignalingServer=binding.etCustomServerUrl.text.toString().trim();binding.tvServerSaved.visibility=View.VISIBLE}
        binding.btnBack.setOnClickListener{finish()}
    }
    private fun qual(p:Int) = when{p<25->"Low (360p)";p<50->"Medium (480p)";p<75->"High (720p)";else->"Ultra HD (1080p)"}
}