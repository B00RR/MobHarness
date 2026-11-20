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
    version = 3,
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
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX index_chat_messages_sessionId_timestamp_id ON chat_messages(sessionId, timestamp, id)")
            }
        }

        @Volatile
        private var INSTANCE: MobHarnDatabase? = null

        internal fun open(context: Context, name: String): MobHarnDatabase =
            Room.databaseBuilder(context.applicationContext, MobHarnDatabase::class.java, name)
                .addMigrations(MIGRATION_2_3)
                .build()

        fun getInstance(context: Context): MobHarnDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: open(context, "mobharn.db").also { INSTANCE = it }
        }
    }
}
