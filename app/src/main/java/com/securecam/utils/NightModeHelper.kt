package com.securecam.utils
import android.graphics.Bitmap; import android.util.Log; import androidx.camera.core.Camera; import com.securecam.utils.AppPreferences; import kotlinx.coroutines.*
class NightModeHelper(private val listener: NightModeListener) {
    private var isNightMode=false; private var camera: Camera?=null
    private val DARK=60; private val BRIGHT=90; private var lastCheck=0L; private val checkInterval=3000L
    private var darkCount=0; private var brightCount=0; private val hysteresis=3
    private val scope=CoroutineScope(Dispatchers.Default+SupervisorJob())
    fun attachCamera(cam: Camera){camera=cam;if(AppPreferences.nightModeEnabled&&!AppPreferences.autoNightModeEnabled)applyNightMode(true)}
    fun analyzeFrameBrightness(bitmap: Bitmap) {
        if(!AppPreferences.autoNightModeEnabled) return
        val now=System.currentTimeMillis(); if(now-lastCheck<checkInterval) return; lastCheck=now
        scope.launch {
            val step=20; val sx=bitmap.width/step; val sy=bitmap.height/step; var total=0L; var count=0
            for(x in 0 until step) for(y in 0 until step){val p=bitmap.getPixel(x*sx,y*sy);total+=((p shr 16 and 0xFF)*299+(p shr 8 and 0xFF)*587+(p and 0xFF)*114)/1000;count++}
            val brightness=(total/count).toInt()
            withContext(Dispatchers.Main){when{brightness<DARK->{darkCount++;brightCount=0;if(darkCount>=hysteresis&&!isNightMode)applyNightMode(true)};brightness>BRIGHT->{brightCount++;darkCount=0;if(brightCount>=hysteresis&&isNightMode)applyNightMode(false)};else->{darkCount=0;brightCount=0}}}
        }
    }
    fun applyNightMode(enable: Boolean){isNightMode=enable;try{camera?.cameraControl?.setExposureCompensationIndex(if(enable)3 else 0)}catch(e:Exception){Log.e("NightMode","Failed: ${e.message}")};listener.onNightModeChanged(enable);AppPreferences.nightModeEnabled=enable}
    fun toggleNightMode(){applyNightMode(!isNightMode)}
    fun isNightModeActive()=isNightMode; fun release(){scope.cancel();camera=null}
    interface NightModeListener{fun onNightModeChanged(isNight:Boolean)}
}