package com.securecam.webrtc

import org.json.JSONObject

object CommandChannel {
    // ── Viewer → Camera commands ─────────────────────────────────────────
    const val CMD_ZOOM            = "zoom"
    const val CMD_NIGHT_MODE      = "nightMode"
    const val CMD_TORCH           = "torch"
    const val CMD_SWITCH_CAMERA   = "switchCamera"
    const val CMD_SNAPSHOT        = "snapshot"          // take & save a still frame
    const val CMD_RECORD_TOGGLE   = "recordToggle"      // manually start/stop recording
    const val CMD_LIST_RECORDINGS = "listRecordings"    // request recording list + HTTP server

    // ── Camera → Viewer events ───────────────────────────────────────────
    const val EVT_MOTION          = "motion"
    const val EVT_FACE            = "face"
    const val EVT_OBJECT          = "object"
    const val EVT_RECORDING       = "recording"
    const val EVT_NIGHT_STATE     = "nightState"
    const val EVT_TORCH_STATE     = "torchState"
    const val EVT_SNAPSHOT_SAVED  = "snapshotSaved"     // camera confirmed snapshot saved
    const val EVT_RECORDING_LIST  = "recordingList"     // JSON array of recording file metadata
    const val EVT_RECORDING_SERVER= "recordingServer"   // camera HTTP server IP + port

    // ── Command builders ─────────────────────────────────────────────────
    fun zoom(ratio: Float)            = JSONObject().put("type", CMD_ZOOM).put("value", ratio).toString()
    fun nightMode(on: Boolean)        = JSONObject().put("type", CMD_NIGHT_MODE).put("on", on).toString()
    fun torch(on: Boolean)            = JSONObject().put("type", CMD_TORCH).put("on", on).toString()
    fun switchCamera()                = JSONObject().put("type", CMD_SWITCH_CAMERA).toString()
    fun snapshot()                    = JSONObject().put("type", CMD_SNAPSHOT).toString()
    fun recordToggle(start: Boolean)  = JSONObject().put("type", CMD_RECORD_TOGGLE).put("start", start).toString()
    fun listRecordings()              = JSONObject().put("type", CMD_LIST_RECORDINGS).toString()

    // ── Event builders ───────────────────────────────────────────────────
    fun evtMotion(score: Float)       = JSONObject().put("type", EVT_MOTION).put("score", score).toString()
    fun evtFace(label: String, known: Boolean) = JSONObject().put("type", EVT_FACE).put("label", label).put("known", known).toString()
    fun evtObject(label: String, conf: Float)  = JSONObject().put("type", EVT_OBJECT).put("label", label).put("confidence", conf).toString()
    fun evtRecording(active: Boolean) = JSONObject().put("type", EVT_RECORDING).put("active", active).toString()
    fun evtNightState(on: Boolean)    = JSONObject().put("type", EVT_NIGHT_STATE).put("on", on).toString()
    fun evtTorchState(on: Boolean)    = JSONObject().put("type", EVT_TORCH_STATE).put("on", on).toString()
    fun evtSnapshotSaved(path: String)= JSONObject().put("type", EVT_SNAPSHOT_SAVED).put("path", path).toString()
    fun evtRecordingList(filesJson: String) = JSONObject().put("type", EVT_RECORDING_LIST).put("files", filesJson).toString()
    fun evtRecordingServer(ip: String, port: Int) =
        JSONObject().put("type", EVT_RECORDING_SERVER).put("ip", ip).put("port", port).toString()
}
