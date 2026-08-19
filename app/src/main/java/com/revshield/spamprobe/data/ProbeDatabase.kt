package com.revshield.spamprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ObservationRecord::class], version = 3, exportSchema = false)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun observations(): ObservationDao

    companion object {
        @Volatile
        private var instance: ProbeDatabase? = null

        /** v3 stores what each source DISPLAYED, so the webhook can report "SPAM | <message>". */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observations ADD COLUMN airtelDisplay TEXT")
                db.execSQL("ALTER TABLE observations ADD COLUMN truecallerDisplay TEXT")
                db.execSQL("UPDATE observations SET airtelDisplay = exactLabelText")
            }
        }

        /** v2 splits the single verdict into per-source verdicts. Existing rows keep their data. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observations ADD COLUMN airtelStatus TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE observations ADD COLUMN truecallerStatus TEXT NOT NULL DEFAULT 'NONE'")
                // Pre-v2 rows only ever read the native dialer, so their verdict IS the carrier one.
                db.execSQL("UPDATE observations SET airtelStatus = spamStatus")
            }
        }

        fun get(context: Context): ProbeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, ProbeDatabase::class.java, "revshield-probe.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
