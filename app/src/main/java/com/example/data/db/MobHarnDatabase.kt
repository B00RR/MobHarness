package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

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
    exportSchema = true
)
abstract class MobHarnDatabase : RoomDatabase() {
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
        private var INSTANCE: MobHarnDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure custom_tools exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_tools` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `parametersJson` TEXT NOT NULL,
                        `implementationType` TEXT NOT NULL,
                        `scriptBody` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `usageCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_tools_name` ON `custom_tools` (`name`)")

                // Ensure tool_execution_logs exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tool_execution_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `argumentsJson` TEXT NOT NULL,
                        `resultSnippet` TEXT NOT NULL,
                        `isError` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sessionId` INTEGER,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_execution_logs_sessionId` ON `tool_execution_logs` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_execution_logs_timestamp` ON `tool_execution_logs` (`timestamp`)")

                // Ensure workspace_snapshots exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `projectName` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `previousContent` TEXT NOT NULL,
                        `newContent` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_snapshots_projectName` ON `workspace_snapshots` (`projectName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_snapshots_relativePath` ON `workspace_snapshots` (`relativePath`)")

                // Ensure FTS table exists
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `chat_messages_fts` 
                    USING FTS4(`content` TEXT, `thinking` TEXT, content=`chat_messages`)
                    """.trimIndent()
                )

                // Ensure columns exist on tables that may have been present in version 1
                addColumnIfNotExists(db, "chat_messages", "thinking", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "chat_messages", "imagePath", "TEXT")
                addColumnIfNotExists(db, "chat_messages", "toolCallsJson", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfNotExists(db, "chat_messages", "tokensPerSec", "REAL NOT NULL DEFAULT 0")

                addColumnIfNotExists(db, "model_profiles", "supportsVision", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "model_profiles", "preferredBackend", "TEXT NOT NULL DEFAULT 'GPU'")
                addColumnIfNotExists(db, "model_profiles", "maxNumTokens", "INTEGER NOT NULL DEFAULT 6144")
                addColumnIfNotExists(db, "model_profiles", "temperature", "REAL NOT NULL DEFAULT 0.7")
                addColumnIfNotExists(db, "model_profiles", "topK", "INTEGER NOT NULL DEFAULT 64")
                addColumnIfNotExists(db, "model_profiles", "topP", "REAL NOT NULL DEFAULT 0.95")
                addColumnIfNotExists(db, "model_profiles", "thinkingEnabled", "INTEGER NOT NULL DEFAULT 1")

                addColumnIfNotExists(db, "automation_tasks", "targetUrl", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "automation_tasks", "intervalMinutes", "INTEGER NOT NULL DEFAULT 60")
                addColumnIfNotExists(db, "automation_tasks", "isPeriodic", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfNotExists(db, "automation_tasks", "enabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfNotExists(db, "automation_tasks", "lastRunAt", "INTEGER")
                addColumnIfNotExists(db, "automation_tasks", "lastResult", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "automation_tasks", "workTag", "TEXT NOT NULL DEFAULT ''")

                // Ensure indices exist
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_entries_category_keyName` ON `memory_entries` (`category`, `keyName`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_skills_slug` ON `agent_skills` (`slug`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_model_profiles_filePath` ON `model_profiles` (`filePath`)")
            }

            private fun addColumnIfNotExists(
                db: SupportSQLiteDatabase,
                tableName: String,
                columnName: String,
                columnDef: String
            ) {
                val cursor = db.query("PRAGMA table_info(`$tableName`)")
                var exists = false
                cursor.use {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx >= 0) {
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameIdx).equals(columnName, ignoreCase = true)) {
                                exists = true
                                break
                            }
                        }
                    }
                }
                if (!exists) {
                    db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDef")
                }
            }
        }

        fun getInstance(context: Context): MobHarnDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    migrateLegacyDatabase(context.applicationContext)
                    Room.databaseBuilder(
                        context.applicationContext,
                        MobHarnDatabase::class.java,
                        "mobharn.db"
                    )
                        .addMigrations(MIGRATION_1_2)
                        .build()
                }.also { INSTANCE = it }
            }
        }

        private fun migrateLegacyDatabase(appContext: Context) {
            runCatching {
                val oldDb = appContext.getDatabasePath("hermes_edge.db")
                val newDb = appContext.getDatabasePath("mobharn.db")
                if (oldDb != null && oldDb.exists() && newDb != null && !newDb.exists()) {
                    newDb.parentFile?.mkdirs()
                    oldDb.copyTo(newDb, overwrite = false)
                    val oldWal = File(oldDb.path + "-wal")
                    val newWal = File(newDb.path + "-wal")
                    if (oldWal.exists() && !newWal.exists()) oldWal.copyTo(newWal, overwrite = false)
                    val oldShm = File(oldDb.path + "-shm")
                    val newShm = File(newDb.path + "-shm")
                    if (oldShm.exists() && !newShm.exists()) oldShm.copyTo(newShm, overwrite = false)
                }
            }
        }
    }
}
