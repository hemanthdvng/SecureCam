package com.securecam.camera

import android.util.Log
import com.securecam.utils.AppPreferences
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Lightweight HTTP server running on the camera phone.
 * Allows the viewer app (on another device, same WiFi) to:
 *   GET /recordings         → JSON list of recording metadata
 *   GET /recording/{name}   → stream an MP4 file
 *
 * Started when a viewer connects, stopped in CameraActivity.onDestroy().
 */
class RecordingHttpServer(port: Int = PORT) : NanoHTTPD(port) {

    private val TAG = "RecordingHttp"

    companion object {
        const val PORT = 8765
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        Log.d(TAG, "GET $uri")

        // CORS header for any future web clients
        val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")

        return when {
            uri == "/recordings" || uri == "/recordings/" -> serveList(corsHeaders)
            uri.startsWith("/recording/")                 -> serveFile(uri.removePrefix("/recording/"), corsHeaders)
            uri == "/health"                              ->
                addHeaders(newFixedLengthResponse("OK"), corsHeaders)
            else ->
                addHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"), corsHeaders)
        }
    }

    private fun serveList(headers: Map<String, String>): Response {
        val dir   = AppPreferences.getRecordingDirectory()
        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val arr = JSONArray()
        files.forEach { f ->
            arr.put(JSONObject().apply {
                put("name",     f.name)
                put("size",     f.length())
                put("modified", f.lastModified())
                put("sizeMb",   "%.1f".format(f.length() / 1_048_576.0))
            })
        }
        return addHeaders(
            newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString()),
            headers
        )
    }

    private fun serveFile(rawName: String, headers: Map<String, String>): Response {
        // Sanitize: strip path traversal
        val safeName = File(rawName).name
        val dir      = AppPreferences.getRecordingDirectory()
        val file     = File(dir, safeName)

        if (!file.exists() || !file.isFile || !file.canonicalPath.startsWith(dir.canonicalPath)) {
            return addHeaders(
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"),
                headers
            )
        }
        return try {
            addHeaders(
                newFixedLengthResponse(Response.Status.OK, "video/mp4", FileInputStream(file), file.length()),
                headers
            )
        } catch (e: Exception) {
            Log.e(TAG, "Serve error: ${e.message}")
            addHeaders(
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error"),
                headers
            )
        }
    }

    private fun addHeaders(resp: Response, headers: Map<String, String>): Response {
        headers.forEach { (k, v) -> resp.addHeader(k, v) }
        return resp
    }
}
