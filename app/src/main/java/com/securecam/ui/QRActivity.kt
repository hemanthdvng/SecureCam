package com.securecam.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.securecam.databinding.ActivityQrBinding
import com.securecam.utils.AppPreferences

class QRActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrBinding

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) parseScanResult(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val roomCode  = intent.getStringExtra(EXTRA_ROOM_CODE) ?: AppPreferences.lastRoomCode
        val serverUrl = AppPreferences.customSignalingServer

        if (roomCode.isNotEmpty()) {
            generateQR(roomCode, serverUrl)
            binding.tvRoomInfo.text = "Room: $roomCode\nServer: ${serverUrl.ifEmpty { "not set" }}"
        } else {
            binding.tvRoomInfo.text = "Start a Camera session first to generate a QR code."
        }

        binding.btnScanQR.setOnClickListener {
            val opts = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan camera phone's QR code")
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
            }
            scanLauncher.launch(opts)
        }
    }

    private fun generateQR(roomCode: String, serverUrl: String) {
        val content = "securecam://join?room=$roomCode&server=${serverUrl.ifEmpty { "none" }}"
        try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
            for (x in 0 until matrix.width)
                for (y in 0 until matrix.height)
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            binding.ivQRCode.setImageBitmap(bmp)
        } catch (e: WriterException) {
            Toast.makeText(this, "QR generation failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseScanResult(content: String) {
        if (content.startsWith("securecam://join")) {
            val uri = Uri.parse(content)
            val room   = uri.getQueryParameter("room")   ?: ""
            val server = uri.getQueryParameter("server") ?: ""
            if (room.isNotEmpty()) {
                AppPreferences.lastRoomCode = room
                if (server.isNotEmpty() && server != "none")
                    AppPreferences.customSignalingServer = server
                Toast.makeText(this, "✅ Room loaded: $room — tap Connect in main menu", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_ROOM_CODE = "extra_qr_room_code"
    }
}
