package com.securecam.webrtc
import android.util.Log; import com.google.gson.Gson; import com.google.gson.JsonObject; import kotlinx.coroutines.*
import okhttp3.*; import okio.ByteString; import java.util.concurrent.TimeUnit
class SignalingClient(private val serverUrl:String, private val roomCode:String, private val isCamera:Boolean, private val listener:SignalingListener) {
    private val gson=Gson(); private var ws:WebSocket?=null; private val scope=CoroutineScope(Dispatchers.IO)
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(25,TimeUnit.SECONDS).build()
    fun connect() {
        ws=client.newWebSocket(Request.Builder().url(serverUrl).build(), object:WebSocketListener(){
            override fun onOpen(w:WebSocket,r:Response){val m=JsonObject().apply{addProperty("type","join");addProperty("room",roomCode);addProperty("role",if(isCamera)"camera" else "viewer")};w.send(gson.toJson(m));scope.launch(Dispatchers.Main){listener.onConnected()}}
            override fun onMessage(w:WebSocket,text:String){try{val j=gson.fromJson(text,JsonObject::class.java);val t=j.get("type")?.asString?:return;scope.launch(Dispatchers.Main){when(t){"offer"->listener.onOfferReceived(j.get("sdp").asString);"answer"->listener.onAnswerReceived(j.get("sdp").asString);"ice_candidate"->listener.onIceCandidateReceived(j.get("candidate").asString,j.get("sdpMid")?.asString?:"",j.get("sdpMLineIndex")?.asInt?:0);"peer_joined"->listener.onPeerJoined();"peer_left"->listener.onPeerLeft();"error"->listener.onError(j.get("message")?.asString?:"Unknown")}}}catch(e:Exception){}}
            override fun onMessage(w:WebSocket,b:ByteString){}
            override fun onClosing(w:WebSocket,code:Int,reason:String){w.close(1000,null);scope.launch(Dispatchers.Main){listener.onDisconnected()}}
            override fun onFailure(w:WebSocket,t:Throwable,r:Response?){scope.launch(Dispatchers.Main){listener.onError(t.message?:"Failed")}}
        })
    }
    fun sendOffer(sdp:String){ws?.send(gson.toJson(JsonObject().apply{addProperty("type","offer");addProperty("sdp",sdp);addProperty("room",roomCode)}))}
    fun sendAnswer(sdp:String){ws?.send(gson.toJson(JsonObject().apply{addProperty("type","answer");addProperty("sdp",sdp);addProperty("room",roomCode)}))}
    fun sendIceCandidate(c:String,mid:String,idx:Int){ws?.send(gson.toJson(JsonObject().apply{addProperty("type","ice_candidate");addProperty("candidate",c);addProperty("sdpMid",mid);addProperty("sdpMLineIndex",idx);addProperty("room",roomCode)}))}
    fun disconnect(){ws?.close(1000,"Bye");ws=null}
    interface SignalingListener{fun onConnected();fun onDisconnected();fun onPeerJoined();fun onPeerLeft();fun onOfferReceived(sdp:String);fun onAnswerReceived(sdp:String);fun onIceCandidateReceived(candidate:String,sdpMid:String,sdpMLineIndex:Int);fun onError(message:String)}
}