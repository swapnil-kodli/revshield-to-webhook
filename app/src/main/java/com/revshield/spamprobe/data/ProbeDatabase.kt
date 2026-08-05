package com.revshield.spamprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ObservationRecord::class], version = 1, exportSchema = false)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun observations(): ObservationDao

    companion object {
        @Volatile
        private var instance: ProbeDatabase? = null

        fun get(context: Context): ProbeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, ProbeDatabase::class.java, "revshield-probe.db")
                    .build()
                    .also { instance = it }
            }
    }
}
