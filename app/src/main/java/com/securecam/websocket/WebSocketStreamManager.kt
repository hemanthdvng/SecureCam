package com.securecam.websocket
import android.graphics.Bitmap; import android.util.Log; import com.google.gson.Gson; import com.google.gson.JsonObject
import kotlinx.coroutines.*; import okhttp3.*; import okio.ByteString; import okio.ByteString.Companion.toByteString; import java.io.ByteArrayOutputStream; import java.util.concurrent.TimeUnit
class WebSocketStreamManager(private val serverUrl:String,private val roomCode:String,private val isCamera:Boolean,private val listener:StreamListener) {
    private val gson=Gson(); private var ws:WebSocket?=null; private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private var lastFrame=0L; private val minInterval=66L
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build()
    fun connect(){ws=client.newWebSocket(Request.Builder().url(serverUrl).build(),object:WebSocketListener(){
        override fun onOpen(w:WebSocket,r:Response){w.send(gson.toJson(JsonObject().apply{addProperty("type","join_stream");addProperty("room",roomCode);addProperty("role",if(isCamera)"sender" else "receiver")}));scope.launch(Dispatchers.Main){listener.onConnected()}}
        override fun onMessage(w:WebSocket,b:ByteString){scope.launch(Dispatchers.Main){listener.onFrameReceived(b.toByteArray())}}
        override fun onMessage(w:WebSocket,text:String){try{val j=gson.fromJson(text,JsonObject::class.java);scope.launch(Dispatchers.Main){when(j.get("type")?.asString){"peer_joined"->listener.onPeerJoined();"peer_left"->listener.onPeerLeft();"motion_event"->listener.onMotionEventReceived(j.get("timestamp")?.asLong?:0);"ai_event"->listener.onAiEventReceived(j.get("label")?.asString?:"",j.get("confidence")?.asFloat?:0f)}}}catch(e:Exception){}}
        override fun onClosing(w:WebSocket,c:Int,r:String){w.close(1000,null);scope.launch(Dispatchers.Main){listener.onDisconnected()}}
        override fun onFailure(w:WebSocket,t:Throwable,r:Response?){scope.launch(Dispatchers.Main){listener.onError(t.message?:"Failed")}}
    })}
    fun sendFrame(bitmap:Bitmap){val now=System.currentTimeMillis();if(now-lastFrame<minInterval)return;lastFrame=now;scope.launch{val b=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,60,b);ws?.send(b.toByteArray().toByteString())}}
    fun sendMotionEvent(){ws?.send(gson.toJson(JsonObject().apply{addProperty("type","motion_event");addProperty("room",roomCode);addProperty("timestamp",System.currentTimeMillis())}))}
    fun sendAiEvent(label:String,confidence:Float){ws?.send(gson.toJson(JsonObject().apply{addProperty("type","ai_event");addProperty("room",roomCode);addProperty("label",label);addProperty("confidence",confidence)}))}
    fun disconnect(){ws?.close(1000,"Bye");ws=null;scope.cancel()}
    interface StreamListener{fun onConnected();fun onDisconnected();fun onPeerJoined();fun onPeerLeft();fun onFrameReceived(frameData:ByteArray);fun onStreamInfo(width:Int,height:Int);fun onMotionEventReceived(timestamp:Long);fun onAiEventReceived(label:String,confidence:Float);fun onError(message:String)}
}