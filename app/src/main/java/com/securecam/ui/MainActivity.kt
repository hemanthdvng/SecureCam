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
        binding.btnCamera.setOnClickListener {
            openConnection(ConnectionActivity.MODE_CAMERA)
        }

        binding.btnViewer.setOnClickListener {
            openConnection(ConnectionActivity.MODE_VIEWER)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Show last used room code if any
        val lastRoom = AppPreferences.lastRoomCode
        if (lastRoom.isNotEmpty()) {
            binding.tvLastRoom.visibility = View.VISIBLE
            binding.tvLastRoom.text = "Last room: $lastRoom"
            binding.tvLastRoom.setOnClickListener {
                openConnectionWithRoom(lastRoom)
            }
        }

        binding.versionText.text = "SecureCam v1.0 • Encrypted Stream"
    }

    private fun openConnection(mode: String) {
        val intent = Intent(this, ConnectionActivity::class.java)
        intent.putExtra(ConnectionActivity.EXTRA_MODE, mode)
        startActivity(intent)
    }

    private fun openConnectionWithRoom(roomCode: String) {
        val intent = Intent(this, ConnectionActivity::class.java)
        intent.putExtra(ConnectionActivity.EXTRA_ROOM_CODE, roomCode)
        startActivity(intent)
    }
}
