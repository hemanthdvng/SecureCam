package com.securecam.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.securecam.databinding.ActivityMainBinding
import com.securecam.utils.AppPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        binding.btnCamera.setOnClickListener { openConnection(ConnectionActivity.MODE_CAMERA) }
        binding.btnViewer.setOnClickListener { openConnection(ConnectionActivity.MODE_VIEWER) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnFaceManagement.setOnClickListener { startActivity(Intent(this, FaceManagementActivity::class.java)) }
        binding.btnTimeline.setOnClickListener { startActivity(Intent(this, TimelineActivity::class.java)) }
        binding.btnQRPair.setOnClickListener { startActivity(Intent(this, QRActivity::class.java)) }

        val lastRoom = AppPreferences.lastRoomCode
        if (lastRoom.isNotEmpty()) {
            binding.tvLastRoom.visibility = View.VISIBLE
            binding.tvLastRoom.text       = "Last room: $lastRoom"
            binding.tvLastRoom.setOnClickListener { openConnectionWithRoom(lastRoom) }
        }
        binding.versionText.text = "SecureCam v4.0 • WatchTower AI • Encrypted Stream"
    }

    private fun openConnection(mode: String) {
        startActivity(Intent(this, ConnectionActivity::class.java).apply {
            putExtra(ConnectionActivity.EXTRA_MODE, mode)
        })
    }

    private fun openConnectionWithRoom(roomCode: String) {
        startActivity(Intent(this, ConnectionActivity::class.java).apply {
            putExtra(ConnectionActivity.EXTRA_ROOM_CODE, roomCode)
        })
    }
}
