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
        NotificationItem::class, ActivityLog::class, AdReward::class
    ],
    version = 2,
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
    abstract fun adRewardDao(): AdRewardDao

    companion object {
        const val DB_NAME = "universalbrain.db"

        /** v1 -> v2: WEB-instance URL/fresh-start columns + ad reward ledger. Non-destructive. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE instances ADD COLUMN startUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE instances ADD COLUMN freshStart INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ad_rewards (" +
                        "id TEXT NOT NULL, at INTEGER NOT NULL, points INTEGER NOT NULL, " +
                        "provider TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(id))"
                )
            }
        }

        fun build(context: Context): BrainDatabase =
            Room.databaseBuilder(context, BrainDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
