package com.universalbrain.ai.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ImportedApp::class, CloneInstance::class, Account::class, AccountSession::class,
        MiningWorker::class, MiningSample::class, Earning::class, AiMessage::class,
        AiMemory::class, AutomationRule::class, BackgroundTask::class,
        NotificationItem::class, ActivityLog::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BrainDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao
    abstract fun cloneDao(): CloneDao
    abstract fun accountDao(): AccountDao
    abstract fun miningDao(): MiningDao
    abstract fun aiDao(): AiDao
    abstract fun automationDao(): AutomationDao
    abstract fun opsDao(): OpsDao

    companion object {
        const val DB_NAME = "universalbrain.db"

        fun build(context: Context): BrainDatabase =
            Room.databaseBuilder(context, BrainDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
