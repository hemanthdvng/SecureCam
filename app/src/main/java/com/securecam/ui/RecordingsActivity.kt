package com.securecam.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.securecam.R
import com.securecam.utils.AppPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        AppPreferences.init(this)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Recordings"
        }

        val dir = AppPreferences.getRecordingDirectory()
        val files = dir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val tvEmpty = findViewById<TextView>(R.id.tvNoRecordings)
        val rv = findViewById<RecyclerView>(R.id.rvRecordings)

        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = RecordingsAdapter(files) { file ->
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open with"))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

class RecordingsAdapter(
    private val files: List<File>,
    private val onPlay: (File) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvRecFileName)
        val tvMeta: TextView = view.findViewById(R.id.tvRecMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = files[position]
        holder.tvName.text = f.name
        val date = SimpleDateFormat("MMM dd, yyyy  HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))
        val mb = "%.1f MB".format(f.length() / 1_048_576.0)
        holder.tvMeta.text = "$date  ·  $mb"
        holder.itemView.setOnClickListener { onPlay(f) }
    }
}
