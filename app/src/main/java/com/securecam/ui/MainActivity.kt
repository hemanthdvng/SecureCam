package com.securecam.ui
import android.content.Intent; import android.os.Bundle; import android.view.View
import androidx.appcompat.app.AppCompatActivity; import com.securecam.databinding.ActivityMainBinding; import com.securecam.utils.AppPreferences
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.btnCamera.setOnClickListener { openConnection(ConnectionActivity.MODE_CAMERA) }
        binding.btnViewer.setOnClickListener { openConnection(ConnectionActivity.MODE_VIEWER) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val last = AppPreferences.lastRoomCode
        if (last.isNotEmpty()) { binding.tvLastRoom.visibility=View.VISIBLE; binding.tvLastRoom.text="Last: $last"; binding.tvLastRoom.setOnClickListener{ openConnectionWithRoom(last) } }
        binding.versionText.text = "SecureCam v1.0 • Encrypted"
    }
    private fun openConnection(mode: String) = startActivity(Intent(this,ConnectionActivity::class.java).putExtra(ConnectionActivity.EXTRA_MODE,mode))
    private fun openConnectionWithRoom(r: String) = startActivity(Intent(this,ConnectionActivity::class.java).putExtra(ConnectionActivity.EXTRA_ROOM_CODE,r))
}