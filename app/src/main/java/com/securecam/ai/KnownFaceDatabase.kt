package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Stores known persons as:
 *  - Name
 *  - 128-dim float embedding (if TFLite model available)
 *  - Small reference thumbnail (JPEG base64) for fallback pixel comparison
 * Persisted as JSON in app's private files directory.
 */
class KnownFaceDatabase(private val context: Context) {

    private val TAG = "KnownFaceDB"
    private val DB_FILE = "known_faces.json"
    private val THUMB_SIZE = 64
    private val entries = mutableListOf<FaceEntry>()

    data class FaceEntry(
        val id: String,
        val name: String,
        val embedding: FloatArray?,       // null if model unavailable
        val thumbnailBase64: String        // always present as fallback
    )

    init { load() }

    fun addFace(name: String, bitmap: Bitmap, embedding: FloatArray?) {
        val id = System.currentTimeMillis().toString()
        val thumb = Bitmap.createScaledBitmap(bitmap, THUMB_SIZE, THUMB_SIZE, true)
        val bos = ByteArrayOutputStream()
        thumb.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val entry = FaceEntry(id, name, embedding, b64)
        entries.removeAll { it.name.equals(name, ignoreCase = true) } // replace if same name
        entries.add(entry)
        save()
        Log.d(TAG, "Added face: $name (embedding=${embedding != null})")
    }

    fun deleteFace(name: String) {
        entries.removeAll { it.name.equals(name, ignoreCase = true) }
        save()
    }

    fun getAllNames(): List<String> = entries.map { it.name }

    fun getAll(): List<FaceEntry> = entries.toList()

    /** Returns best match name + similarity score, or null if no match above threshold */
    fun findMatch(embedding: FloatArray, threshold: Float = 0.55f): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        for (entry in entries) {
            val emb = entry.embedding ?: continue
            val sim = cosineSimilarity(embedding, emb)
            if (sim > threshold && (best == null || sim > best.second)) {
                best = Pair(entry.name, sim)
            }
        }
        return best
    }

    /** Pixel-level fallback comparison when no embedding available */
    fun findMatchByPixel(faceBitmap: Bitmap, threshold: Float = 0.75f): Pair<String, Float>? {
        val query = Bitmap.createScaledBitmap(faceBitmap, THUMB_SIZE, THUMB_SIZE, true)
        var best: Pair<String, Float>? = null
        for (entry in entries) {
            try {
                val refBytes = Base64.decode(entry.thumbnailBase64, Base64.NO_WRAP)
                val ref = android.graphics.BitmapFactory.decodeByteArray(refBytes, 0, refBytes.size)
                val score = pixelSimilarity(query, ref)
                if (score > threshold && (best == null || score > best.second)) {
                    best = Pair(entry.name, score)
                }
            } catch (e: Exception) { /* skip corrupt entry */ }
        }
        return best
    }

    private fun pixelSimilarity(a: Bitmap, b: Bitmap): Float {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        var match = 0; var total = 0
        for (x in 0 until w step 4) {
            for (y in 0 until h step 4) {
                val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
                val dr = Math.abs((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF))
                val dg = Math.abs((pa shr 8  and 0xFF) - (pb shr 8  and 0xFF))
                val db = Math.abs((pa        and 0xFF) - (pb        and 0xFF))
                if (dr + dg + db < 90) match++
                total++
            }
        }
        return if (total > 0) match.toFloat() / total else 0f
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices.take(minOf(a.size, b.size))) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val denom = Math.sqrt((na * nb).toDouble()).toFloat()
        return if (denom > 0f) dot / denom else 0f
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val obj = JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("thumb", e.thumbnailBase64)
                if (e.embedding != null) {
                    val embArr = JSONArray(); e.embedding.forEach { embArr.put(it.toDouble()) }
                    obj.put("embedding", embArr)
                }
                arr.put(obj)
            }
            File(context.filesDir, DB_FILE).writeText(arr.toString())
        } catch (e: Exception) { Log.e(TAG, "Save failed: ${e.message}") }
    }

    private fun load() {
        try {
            val file = File(context.filesDir, DB_FILE)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val embArr = obj.optJSONArray("embedding")
                val emb = if (embArr != null) {
                    FloatArray(embArr.length()) { idx -> embArr.getDouble(idx).toFloat() }
                } else null
                entries.add(FaceEntry(obj.getString("id"), obj.getString("name"), emb, obj.getString("thumb")))
            }
            Log.d(TAG, "Loaded ${entries.size} known faces")
        } catch (e: Exception) { Log.e(TAG, "Load failed: ${e.message}") }
    }

    fun isEmpty() = entries.isEmpty()
}
