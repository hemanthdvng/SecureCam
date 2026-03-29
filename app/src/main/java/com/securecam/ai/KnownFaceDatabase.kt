package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.ByteArrayOutputStream

/**
 * KnownFaceDatabase v3 — multi-embedding support.
 *
 * Storage format change (no Room migration needed — same TEXT column):
 *   Legacy (v1/v2): embeddingJson = "[f1, f2, ..., f128]"   ← single flat array
 *   New (v3):       embeddingJson = "[[f1,...],[f1,...]]"    ← array of embedding arrays
 *
 * findMatch() now scores against ALL stored embeddings for a person and takes
 * the best match, dramatically improving accuracy when multiple samples are enrolled.
 *
 * Threshold raised from 0.55 → 0.65 now that enrollments are cropped properly.
 */
class KnownFaceDatabase(context: Context) {

    private val TAG = "KnownFaceDB"
    private val db  = PersonDatabase.getInstance(context)
    private val dao = db.personDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var cache: List<KnownPerson> = emptyList()

    init { scope.launch { cache = dao.getAll(); Log.d(TAG, "Loaded ${cache.size} known faces") } }

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Add a face sample for [name]. If the person already exists, the new
     * embedding is appended (up to MAX_EMBEDDINGS_PER_PERSON). This lets
     * callers enroll multiple photos for better accuracy without re-registering.
     */
    fun addFace(name: String, bitmap: Bitmap, embedding: FloatArray?) {
        scope.launch {
            val b64 = bitmap.toBase64()
            val existing = dao.getAll().find { it.name == name }

            val newEmbJson: String = if (embedding != null) {
                val existingList: MutableList<FloatArray> = if (existing != null) {
                    parseEmbeddingList(existing.embeddingJson).toMutableList()
                } else {
                    mutableListOf()
                }
                existingList.add(embedding)
                // Cap at 5 samples per person
                if (existingList.size > MAX_EMBEDDINGS_PER_PERSON)
                    existingList.removeAt(0)
                embeddingListToJson(existingList)
            } else {
                existing?.embeddingJson ?: "[]"
            }

            // Keep most recent thumbnail
            dao.upsert(KnownPerson(name = name, embeddingJson = newEmbJson, thumbnailB64 = b64))
            cache = dao.getAll()
            val count = parseEmbeddingList(newEmbJson).size
            Log.d(TAG, "Enrolled: $name — now has $count embedding(s)")
        }
    }

    fun deleteFace(name: String) {
        scope.launch { dao.deleteByName(name); cache = dao.getAll() }
    }

    // ── Read API ──────────────────────────────────────────────────────────────

    fun getAllNames(): List<String> = cache.map { it.name }
    fun getAll(): List<KnownPerson> = cache
    fun isEmpty(): Boolean = cache.isEmpty()

    /** How many embedding samples are stored for [name]. Returns 0 if unknown. */
    fun getEmbeddingCount(name: String): Int {
        val p = cache.find { it.name == name } ?: return 0
        return parseEmbeddingList(p.embeddingJson).size
    }

    /**
     * Match [embedding] against all stored embeddings.
     *
     * Scoring:
     *  - Each person's best-matching sample is used (max across all their embeddings)
     *  - Threshold raised to 0.65 for properly-cropped embeddings
     *  - Returns (name, bestSimilarity) or null if nothing passes threshold
     */
    fun findMatch(embedding: FloatArray, threshold: Float = MATCH_THRESHOLD): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        for (p in cache) {
            val embeddings = parseEmbeddingList(p.embeddingJson)
            if (embeddings.isEmpty()) continue

            // Best score across all samples for this person
            var personBest = 0f
            for (stored in embeddings) {
                val sim = cosineSimilarity(embedding, stored)
                if (sim > personBest) personBest = sim
            }

            if (personBest > threshold && (best == null || personBest > best.second)) {
                best = p.name to personBest
            }
        }
        return best
    }

    /** Pixel-level fallback when model unavailable */
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

    // ── Embedding JSON helpers ────────────────────────────────────────────────

    /**
     * Parse either legacy format "[f1, f2, ...]" (single flat array)
     * or new format "[[f1,...],[f1,...]]" (list of arrays).
     * Returns an empty list if unparseable or "[]".
     */
    private fun parseEmbeddingList(json: String): List<FloatArray> {
        if (json == "[]" || json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return emptyList()

            // Detect format by inspecting the first element
            if (arr.get(0) is org.json.JSONArray) {
                // New format: array of arrays
                List(arr.length()) { i ->
                    val inner = arr.getJSONArray(i)
                    FloatArray(inner.length()) { j -> inner.getDouble(j).toFloat() }
                }
            } else {
                // Legacy format: single flat array — wrap in a list
                listOf(FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() })
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseEmbeddingList failed: ${e.message}")
            emptyList()
        }
    }

    private fun embeddingListToJson(embeddings: List<FloatArray>): String {
        val outer = JSONArray()
        for (emb in embeddings) {
            val inner = JSONArray()
            emb.forEach { inner.put(it.toDouble()) }
            outer.put(inner)
        }
        return outer.toString()
    }

    // ── Math ─────────────────────────────────────────────────────────────────

    /**
     * Cosine similarity between two vectors.
     * Both should be L2-normalized (from generateEmbedding), so this is
     * equivalent to the dot product, but we compute the full form for safety
     * in case stored legacy data has precision drift from JSON round-trips.
     */
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
                    Math.abs((pa shr  8 and 0xFF) - (pb shr  8 and 0xFF)) +
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

    companion object {
        /** Max embedding samples stored per person */
        const val MAX_EMBEDDINGS_PER_PERSON = 5

        /**
         * Cosine similarity threshold for a match.
         * Raised from 0.55 → 0.65 now that enrollment uses properly-cropped faces.
         * MobileFaceNet on well-aligned 112×112 crops: same-person scores typically 0.70–0.95,
         * different-person scores typically 0.20–0.50.
         */
        const val MATCH_THRESHOLD = 0.65f
    }
}
