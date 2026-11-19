package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatMessageFtsEntity::class,
        MemoryEntryEntity::class,
        AgentSkillEntity::class,
        WorkspaceSnapshotEntity::class,
        AutomationTaskEntity::class,
        ModelProfileEntity::class,
        ToolExecutionLogEntity::class,
        CustomToolEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun workspaceSnapshotDao(): WorkspaceSnapshotDao
    abstract fun automationDao(): AutomationDao
    abstract fun modelProfileDao(): ModelProfileDao
    abstract fun toolLogDao(): ToolLogDao
    abstract fun customToolDao(): CustomToolDao

    companion object {
        @Volatile
        private var INSTANCE: HermesDatabase? = null

        fun getInstance(context: Context): HermesDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HermesDatabase::class.java,
                    "hermes_edge.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
