package com.securecam.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [KnownPerson::class], version = 1, exportSchema = false)
abstract class PersonDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao

    companion object {
        @Volatile private var INSTANCE: PersonDatabase? = null

        fun getInstance(context: Context): PersonDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PersonDatabase::class.java,
                    "securecam_faces.db"
                ).build().also { INSTANCE = it }
            }
    }
}
