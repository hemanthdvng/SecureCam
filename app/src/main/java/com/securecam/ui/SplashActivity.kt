package com.securecam.ui
import android.annotation.SuppressLint; import android.content.Intent; import android.os.Bundle; import android.os.Handler; import android.os.Looper
import androidx.appcompat.app.AppCompatActivity; import com.securecam.databinding.ActivitySplashBinding
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater); setContentView(binding.root)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); finish()
        }, 2000)
    }
}