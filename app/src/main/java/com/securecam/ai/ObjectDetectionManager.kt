package com.securecam.ai
import android.graphics.Bitmap; import android.util.Log
import com.google.mlkit.vision.common.InputImage; import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector; import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
class ObjectDetectionManager(private val listener: ObjectDetectionListener) {
    private var detector: ObjectDetector? = null; private var isProcessing=false; private var lastTime=0L; private val interval=500L
    private val recentLabels = mutableMapOf<String,Long>(); private val cooldown=5000L
    fun initialize() {
        detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().enableClassification().build())
    }
    fun processFrame(bitmap: Bitmap) {
        val now=System.currentTimeMillis(); if(isProcessing||now-lastTime<interval) return; isProcessing=true; lastTime=now
        detector?.process(InputImage.fromBitmap(bitmap,0))?.addOnSuccessListener{objects->
            val results=objects.mapNotNull{obj->val labels=obj.labels.map{ObjectLabel(it.text,it.confidence,it.index)};if(labels.isEmpty())null else{val top=labels.maxByOrNull{it.confidence}!!;DetectedObject(obj.trackingId?:-1,labels,top,BoundingBox(obj.boundingBox.left,obj.boundingBox.top,obj.boundingBox.right,obj.boundingBox.bottom))}}
            if(results.isNotEmpty()) listener.onObjectsDetected(results)
            results.forEach{if(it.topLabel.confidence>0.7f){val t=System.currentTimeMillis();if(t-(recentLabels[it.topLabel.text]?:0)>cooldown){recentLabels[it.topLabel.text]=t;listener.onObjectAlert(it.topLabel.text,it.topLabel.confidence)}}}
            isProcessing=false
        }?.addOnFailureListener{isProcessing=false}
    }
    fun release(){detector?.close();detector=null}
    data class ObjectLabel(val text:String,val confidence:Float,val index:Int)
    data class BoundingBox(val left:Int,val top:Int,val right:Int,val bottom:Int)
    data class DetectedObject(val id:Int,val labels:List<ObjectLabel>,val topLabel:ObjectLabel,val boundingBox:BoundingBox)
    interface ObjectDetectionListener{fun onObjectsDetected(objects:List<DetectedObject>);fun onObjectAlert(label:String,confidence:Float)}
}