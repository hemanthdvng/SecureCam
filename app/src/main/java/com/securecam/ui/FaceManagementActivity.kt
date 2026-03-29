package com.securecam.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
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

    // ML Kit detector configured for high accuracy enrollment
    private val enrollDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.10f)
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
                    Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val stream = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(stream)
                detectAndEnroll(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
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

                // Check if person already exists — offer to add another sample
                val existing = faceDb.getAll().find { it.name == name }
                if (existing != null) {
                    val sampleCount = faceDb.getEmbeddingCount(name)
                    AlertDialog.Builder(this)
                        .setTitle("$name already enrolled")
                        .setMessage("$name has $sampleCount sample(s). Adding more photos improves accuracy (max 5).\n\nWhat would you like to do?")
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
        val sampleCount = faceDb.getEmbeddingCount(pendingName)
        val remaining = 5 - sampleCount
        val hint = if (sampleCount > 0) " ($sampleCount/5 samples — $remaining more allowed)" else ""

        val options = arrayOf("📷 Take Photo", "🖼️ Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Photo for: $pendingName$hint")
            .setMessage("Look directly at the camera. Good lighting improves accuracy.")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickImage.launch("image/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "face_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        pendingCameraUri = uri
        uri?.let { takePicture.launch(it) }
    }

    /**
     * FIXED: Run ML Kit face detection on the enrollment image first.
     * Crop and align the detected face before generating the embedding.
     * Previously this used the raw full-image bitmap → garbage embeddings.
     */
    private fun detectAndEnroll(rawBitmap: Bitmap) {
        Toast.makeText(this, "Detecting face for $pendingName…", Toast.LENGTH_SHORT).show()

        val image = InputImage.fromBitmap(rawBitmap, 0)
        enrollDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    Toast.makeText(
                        this,
                        "⚠️ No face detected. Please use a clear, front-facing photo with good lighting.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@addOnSuccessListener
                }

                // Pick the largest detected face if multiple
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                val bb = face.boundingBox
                val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                // Crop + align using eye landmarks when available
                val faceCrop = if (leftEye != null && rightEye != null) {
                    recognitionManager.cropAndAlignFace(
                        rawBitmap,
                        bb.left, bb.top, bb.right, bb.bottom,
                        leftEye.x, leftEye.y, rightEye.x, rightEye.y
                    )
                } else {
                    recognitionManager.cropFace(rawBitmap, bb.left, bb.top, bb.right, bb.bottom)
                }

                lifecycleScope.launch {
                    val embedding = withContext(Dispatchers.Default) {
                        recognitionManager.generateEmbedding(faceCrop)
                    }

                    if (embedding == null) {
                        Toast.makeText(
                            this@FaceManagementActivity,
                            "❌ Embedding failed — model not loaded. Check assets/mobilefacenet.tflite.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    val currentCount = faceDb.getEmbeddingCount(pendingName)
                    if (currentCount >= 5) {
                        Toast.makeText(
                            this@FaceManagementActivity,
                            "⚠️ Maximum 5 samples reached for $pendingName. Delete and re-enroll to reset.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    // Add the cropped face crop (not the full raw photo) to the database
                    faceDb.addFace(pendingName, faceCrop, embedding)
                    delay(500)
                    loadFaces()

                    val newCount = currentCount + 1
                    val tip = when {
                        newCount < 3 -> " Add ${3 - newCount} more for best accuracy."
                        newCount == 3 -> " Good! You can add up to 2 more."
                        else -> " Excellent coverage!"
                    }
                    Toast.makeText(
                        this@FaceManagementActivity,
                        "✅ Sample $newCount/5 added for $pendingName.$tip",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Detection error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showDeleteDialog(person: KnownPerson) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${person.name}?")
            .setMessage("This will permanently remove all ${faceDb.getEmbeddingCount(person.name)} sample(s) for this person.")
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

// ── RecyclerView Adapter ──────────────────────────────────────────────────────

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
