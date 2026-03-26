package com.securecam.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.securecam.R
import com.securecam.ai.EventDatabase
import com.securecam.ai.EventRecord
import com.securecam.databinding.ActivityTimelineBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private lateinit var eventDb: EventDatabase
    private var eventAdapter: EventAdapter? = null
    private var currentFilter = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eventDb = EventDatabase.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener { clearAllEvents() }

        setupFilters()
        setupRecyclerView()
        setFilter("all")
    }

    private fun setupFilters() {
        binding.btnFilterAll.setOnClickListener    { setFilter("all") }
        binding.btnFilterMotion.setOnClickListener  { setFilter("motion") }
        binding.btnFilterFace.setOnClickListener   { setFilter("face") }
        binding.btnFilterObject.setOnClickListener { setFilter("object") }
    }

    private fun setFilter(filter: String) {
        currentFilter = filter
        listOf("all" to binding.btnFilterAll, "motion" to binding.btnFilterMotion,
               "face" to binding.btnFilterFace, "object" to binding.btnFilterObject)
            .forEach { (type, btn) -> btn.alpha = if (type == filter) 1f else 0.45f }
        loadEvents(filter)
    }

    private fun setupRecyclerView() {
        eventAdapter = EventAdapter(emptyList())
        binding.rvEvents.layoutManager = LinearLayoutManager(this)
        binding.rvEvents.adapter = eventAdapter
    }

    private fun loadEvents(filter: String) {
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) {
                when (filter) {
                    "all" -> eventDb.eventDao().getAll()
                    "face" -> {
                        val k = eventDb.eventDao().getByType("face_known")
                        val u = eventDb.eventDao().getByType("face_unknown")
                        (k + u).sortedByDescending { it.timestamp }
                    }
                    else -> eventDb.eventDao().getByType(filter)
                }
            }
            eventAdapter?.updateData(events)
            binding.tvEmptyState.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            binding.tvEventCount.text = "${events.size} events"
        }
    }

    private fun clearAllEvents() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { eventDb.eventDao().deleteAll() }
            loadEvents(currentFilter)
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class EventAdapter(private var events: List<EventRecord>) :
    RecyclerView.Adapter<EventAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView     = view.findViewById(R.id.tvEventTypeIcon)
        val tvLabel: TextView    = view.findViewById(R.id.tvEventLabel)
        val tvTime: TextView     = view.findViewById(R.id.tvEventTime)
        val tvPriority: TextView = view.findViewById(R.id.tvEventPriority)
    }

    private val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    fun updateData(newEvents: List<EventRecord>) { events = newEvents; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false))

    override fun getItemCount() = events.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val e = events[pos]
        holder.tvIcon.text = when (e.type) {
            "motion"       -> "⚠️"
            "face_known"   -> "✅"
            "face_unknown" -> "🚨"
            "object"       -> "🔍"
            "recording"    -> "🔴"
            else           -> "📋"
        }
        holder.tvLabel.text = when {
            e.label.isNotEmpty() && e.confidence > 0f ->
                "${e.label}  (${(e.confidence * 100).toInt()}%)"
            e.label.isNotEmpty() -> e.label
            e.type == "motion"       -> "Motion detected  (${(e.confidence * 100).toInt()}%)"
            e.type == "face_unknown" -> "Unknown person detected"
            else -> e.type.replaceFirstChar { it.uppercase() }
        }
        holder.tvTime.text = fmt.format(Date(e.timestamp))
        holder.tvPriority.text = when (e.priority) {
            "critical" -> "🔴 CRITICAL"
            "warning"  -> "🟡 WARNING"
            else       -> ""
        }
        holder.tvPriority.visibility =
            if (e.priority != "normal") View.VISIBLE else View.GONE
    }
}
