package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream

/**
 * Public face-store API used by FaceDetectionManager.
 * All persistence is delegated to Room (PersonDatabase / PersonDao).
 * Heavy operations run on IO dispatcher; callbacks return on the caller's thread.
 */
class KnownFaceDatabase(context: Context) {

    private val TAG = "KnownFaceDB"
    private val db = PersonDatabase.getInstance(context)
    private val dao = db.personDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    // In-memory snapshot refreshed after every write
    @Volatile private var cache: List<KnownPerson> = emptyList()

    init { scope.launch { cache = dao.getAll(); Log.d(TAG, "Loaded ${cache.size} known faces from Room") } }

    // ── Write API ─────────────────────────────────────────────────────────

    fun addFace(name: String, bitmap: Bitmap, embedding: FloatArray?) {
        scope.launch {
            val b64 = bitmap.toBase64()
            val embJson = embedding?.let { floatArrayToJson(it) } ?: "[]"
            dao.upsert(KnownPerson(name = name, embeddingJson = embJson, thumbnailB64 = b64))
            cache = dao.getAll()
            Log.d(TAG, "Enrolled: $name (embedding=${embedding != null})")
        }
    }

    fun deleteFace(name: String) {
        scope.launch { dao.deleteByName(name); cache = dao.getAll() }
    }

    // ── Read API (synchronous on snapshot) ───────────────────────────────

    fun getAllNames(): List<String> = cache.map { it.name }
    fun getAll(): List<KnownPerson> = cache
    fun isEmpty(): Boolean = cache.isEmpty()

    /**
     * Cosine-similarity match using 128-dim embeddings.
     * Returns (name, similarity) or null if nothing exceeds [threshold].
     */
    fun findMatch(embedding: FloatArray, threshold: Float = 0.55f): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        for (p in cache) {
            if (p.embeddingJson == "[]") continue
            val ke = jsonToFloatArray(p.embeddingJson) ?: continue
            val sim = cosineSimilarity(embedding, ke)
            if (sim > threshold && (best == null || sim > best.second))
                best = p.name to sim
        }
        return best
    }

    /** Pixel-level fallback when model is unavailable */
    fun findMatchByPixel(faceBitmap: Bitmap, threshold: Float = 0.72f): Pair<String, Float>? {
        val query = Bitmap.createScaledBitmap(faceBitmap, 64, 64, true)
        var best: Pair<String, Float>? = null
        for (p in cache) {
            if (p.thumbnailB64.isEmpty()) continue
            try {
                val bytes = Base64.decode(p.thumbnailB64, Base64.NO_WRAP)
                val ref = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val score = pixelSimilarity(query, ref)
                if (score > threshold && (best == null || score > best.second))
                    best = p.name to score
            } catch (_: Exception) {}
        }
        return best
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun floatArrayToJson(a: FloatArray): String {
        val arr = JSONArray(); a.forEach { arr.put(it.toDouble()) }; return arr.toString()
    }

    private fun jsonToFloatArray(json: String): FloatArray? = try {
        val arr = JSONArray(json); FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    } catch (_: Exception) { null }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = Math.sqrt((na * nb).toDouble()).toFloat()
        return if (denom > 1e-6f) dot / denom else 0f
    }

    private fun pixelSimilarity(a: Bitmap, b: Bitmap): Float {
        val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
        var match = 0; var total = 0
        for (x in 0 until w step 4) for (y in 0 until h step 4) {
            val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
            val d = Math.abs((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF)) +
                    Math.abs((pa shr 8  and 0xFF) - (pb shr 8  and 0xFF)) +
                    Math.abs((pa        and 0xFF) - (pb        and 0xFF))
            if (d < 90) match++
            total++
        }
        return if (total > 0) match.toFloat() / total else 0f
    }

    private fun Bitmap.toBase64(): String {
        val bos = ByteArrayOutputStream()
        Bitmap.createScaledBitmap(this, 64, 64, true).compress(Bitmap.CompressFormat.JPEG, 75, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
