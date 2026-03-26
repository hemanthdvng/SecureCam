package com.securecam.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EventRecord::class], version = 1, exportSchema = false)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var INSTANCE: EventDatabase? = null

        fun getInstance(context: Context): EventDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "securecam_events.db"
                ).build().also { INSTANCE = it }
            }
    }
}
