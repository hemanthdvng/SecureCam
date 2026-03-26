package com.securecam.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,           // "motion" | "face_known" | "face_unknown" | "object" | "recording"
    val label: String = "",
    val confidence: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val thumbnailB64: String = "",
    val priority: String = "normal"   // "normal" | "warning" | "critical"
)
