package com.securecam.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity: one row per enrolled person.
 * embeddingJson  — JSON array of 128 floats from MobileFaceNet
 * thumbnailB64   — base64 JPEG thumbnail for fallback pixel comparison
 */
@Entity(tableName = "known_persons")
data class KnownPerson(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val embeddingJson: String,    // "[0.12,-0.34,...]"
    val thumbnailB64: String = "" // base64 JPEG, for pixel fallback
)
