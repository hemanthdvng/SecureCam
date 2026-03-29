package com.securecam.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.securecam.R
import com.securecam.ai.FaceRecognitionManager
import com.securecam.ai.KnownFaceDatabase
import com.securecam.ai.KnownPerson
import com.securecam.ai.PersonDatabase
import com.securecam.databinding.ActivityFaceManagementBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FaceManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceManagementBinding
    private lateinit var faceDb: KnownFaceDatabase
    private lateinit var recognitionManager: FaceRecognitionManager
    private var faceAdapter: FaceAdapter? = null

    private var pendingName = ""
    private var pendingCameraUri: Uri? = null

    private val enrollDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                try {
                    @Suppress("DEPRECATION")
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    detectAndEnroll(bitmap)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error loading photo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val stream = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                if (bitmap == null) {
                    Toast.makeText(this, "Could not decode image. Try a different photo.", Toast.LENGTH_LONG).show()
                    return@let
                }
                detectAndEnroll(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        faceDb = KnownFaceDatabase(this)
        recognitionManager = FaceRecognitionManager(this).also { it.initialize() }

        binding.btnBack.setOnClickListener { finish() }
        binding.fabAddFace.setOnClickListener { showAddFaceDialog() }

        setupRecyclerView()
        loadFaces()
    }

    private fun setupRecyclerView() {
        faceAdapter = FaceAdapter(emptyList()) { person -> showDeleteDialog(person) }
        binding.rvFaces.layoutManager = LinearLayoutManager(this)
        binding.rvFaces.adapter = faceAdapter
    }

    private fun loadFaces() {
        lifecycleScope.launch {
            val persons = withContext(Dispatchers.IO) {
                PersonDatabase.getInstance(this@FaceManagementActivity).personDao().getAll()
            }
            faceAdapter?.updateData(persons)
            binding.tvEmptyState.visibility = if (persons.isEmpty()) View.VISIBLE else View.GONE
            binding.tvFaceCount.text = "${persons.size} enrolled"
        }
    }

    private fun showAddFaceDialog() {
        val editText = EditText(this).apply {
            hint = "Enter person's name"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("👤 Add Known Face")
            .setView(editText)
            .setPositiveButton("Next") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingName = name
                val existing = faceDb.getAll().find { it.name == name }
                if (existing != null) {
                    val count = faceDb.getEmbeddingCount(name)
                    AlertDialog.Builder(this)
                        .setTitle("$name already enrolled ($count/5 samples)")
                        .setMessage("Adding more photos improves accuracy.")
                        .setPositiveButton("Add another photo") { _, _ -> showImageSourceDialog() }
                        .setNegativeButton("Replace all") { _, _ ->
                            faceDb.deleteFace(name)
                            showImageSourceDialog()
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    showImageSourceDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImageSourceDialog() {
        val count = faceDb.getEmbeddingCount(pendingName)
        val title = if (count > 0) "Photo for $pendingName ($count/5)" else "Photo for $pendingName"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("📷 Take Photo", "🖼️ Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickImage.launch("image/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "face_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "Could not create camera file. Try Gallery instead.", Toast.LENGTH_LONG).show()
                return
            }
            pendingCameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Detect and enroll a face from [rawBitmap].
     *
     * Flow:
     *  1. Run ML Kit face detection on the photo.
     *  2. If a face is found → crop + align → generate embedding → save.
     *  3. If NO face is found → offer the user a choice:
     *       a. "Try another photo" (go back)
     *       b. "Add anyway" → use a padded center crop as fallback.
     *          This lets enrollment proceed even when ML Kit can't detect the face
     *          (e.g. unusual lighting, heavy occlusion, very small face in the photo).
     *          Recognition accuracy will be lower for this sample.
     */
    private fun detectAndEnroll(rawBitmap: Bitmap) {
        if (rawBitmap.width == 0 || rawBitmap.height == 0) {
            Toast.makeText(this, "Invalid image. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Analysing photo…", Toast.LENGTH_SHORT).show()

        val image = InputImage.fromBitmap(rawBitmap, 0)
        enrollDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    // ── No face detected — offer graceful fallback ──────────
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ No face detected")
                        .setMessage(
                            "ML Kit couldn't find a face in this photo.\n\n" +
                            "Tips for best results:\n" +
                            "• Use a clear front-facing photo\n" +
                            "• Good lighting, face unobstructed\n" +
                            "• Face should fill most of the frame\n\n" +
                            "You can still add this photo (lower accuracy) or try another."
                        )
                        .setPositiveButton("Try another photo") { _, _ -> showImageSourceDialog() }
                        .setNegativeButton("Add anyway") { _, _ ->
                            // Fallback: padded center crop
                            val crop = centerCrop(rawBitmap)
                            saveEmbedding(rawBitmap, crop, isFallback = true)
                        }
                        .show()
                    return@addOnSuccessListener
                }

                // Pick the largest face if there are multiple
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                val bb       = face.boundingBox
                val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                val faceCrop = if (leftEye != null && rightEye != null) {
                    recognitionManager.cropAndAlignFace(
                        rawBitmap,
                        bb.left, bb.top, bb.right, bb.bottom,
                        leftEye.x, leftEye.y, rightEye.x, rightEye.y
                    )
                } else {
                    recognitionManager.cropFace(rawBitmap, bb.left, bb.top, bb.right, bb.bottom)
                }

                saveEmbedding(rawBitmap, faceCrop, isFallback = false)
            }
            .addOnFailureListener { e ->
                // ML Kit init failure — fall back gracefully rather than blocking
                Toast.makeText(this, "Face detector unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
                val crop = centerCrop(rawBitmap)
                saveEmbedding(rawBitmap, crop, isFallback = true)
            }
    }

    /** Generate embedding and persist the face sample. */
    private fun saveEmbedding(thumbnailBitmap: Bitmap, faceCrop: Bitmap, isFallback: Boolean) {
        lifecycleScope.launch {
            val currentCount = faceDb.getEmbeddingCount(pendingName)
            if (currentCount >= 5) {
                Toast.makeText(
                    this@FaceManagementActivity,
                    "⚠️ Max 5 samples reached for $pendingName. Delete and re-enroll to reset.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val embedding = withContext(Dispatchers.Default) {
                recognitionManager.generateEmbedding(faceCrop)
            }

            if (embedding == null && !isFallback) {
                Toast.makeText(
                    this@FaceManagementActivity,
                    "❌ Model not loaded — check assets/mobilefacenet.tflite",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // Use the cropped face as thumbnail for enrolled view, raw for storage
            faceDb.addFace(pendingName, faceCrop, embedding)
            delay(400)
            loadFaces()

            val newCount = currentCount + 1
            val quality  = if (isFallback || embedding == null) " (⚠️ fallback — lower accuracy)" else ""
            val tip = when {
                newCount < 3 -> " Add ${3 - newCount} more for best accuracy."
                newCount == 3 -> " Good. 2 more allowed."
                newCount >= 5 -> " Full coverage!"
                else -> ""
            }
            Toast.makeText(
                this@FaceManagementActivity,
                "✅ Sample $newCount/5 saved for $pendingName$quality.$tip",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Padded center crop fallback when face detection fails. */
    private fun centerCrop(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val x    = (bitmap.width  - size) / 2
        val y    = (bitmap.height - size) / 4  // slightly above center — typical face position
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    private fun showDeleteDialog(person: KnownPerson) {
        val count = faceDb.getEmbeddingCount(person.name)
        AlertDialog.Builder(this)
            .setTitle("Remove ${person.name}?")
            .setMessage("This removes all $count sample(s) for this person.")
            .setPositiveButton("Remove") { _, _ ->
                faceDb.deleteFace(person.name)
                lifecycleScope.launch { delay(300); loadFaces() }
                Toast.makeText(this, "Removed ${person.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognitionManager.release()
        enrollDetector.close()
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class FaceAdapter(
    private var persons: List<KnownPerson>,
    private val onDelete: (KnownPerson) -> Unit
) : RecyclerView.Adapter<FaceAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView  = view.findViewById(R.id.ivFaceThumbnail)
        val tvName: TextView        = view.findViewById(R.id.tvFaceName)
        val tvStatus: TextView      = view.findViewById(R.id.tvEmbeddingStatus)
        val btnDelete: ImageButton  = view.findViewById(R.id.btnDeleteFace)
    }

    fun updateData(newPersons: List<KnownPerson>) {
        persons = newPersons
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_known_face, parent, false))

    override fun getItemCount() = persons.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val p = persons[pos]
        holder.tvName.text = p.name
        holder.tvStatus.text = if (p.embeddingJson != "[]") "✅ AI embedding" else "⚠️ Pixel fallback"
        holder.btnDelete.setOnClickListener { onDelete(p) }
        if (p.thumbnailB64.isNotEmpty()) {
            try {
                val bytes = Base64.decode(p.thumbnailB64, Base64.NO_WRAP)
                holder.ivThumbnail.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } catch (_: Exception) {
                holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else {
            holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }
}
