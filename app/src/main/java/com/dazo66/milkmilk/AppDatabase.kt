package com.dazo66.milkmilk

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppUsageRecord::class,
        AggregatedBehavior::class,
        AggregatedBehaviorSource::class,
        DeletedUsageSession::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appUsageDao(): AppUsageDao

    // 新增：聚合行为 DAO
    abstract fun aggregatedBehaviorDao(): AggregatedBehaviorDao

    abstract fun deletedUsageSessionDao(): DeletedUsageSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_usage_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `deleted_usage_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `deletedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deleted_usage_sessions_packageName_startTime_endTime` " +
                        "ON `deleted_usage_sessions` (`packageName`, `startTime`, `endTime`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deleted_usage_sessions_deletedAt` " +
                        "ON `deleted_usage_sessions` (`deletedAt`)"
                )
            }
        }
    }
}
