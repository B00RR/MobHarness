package com.example.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobHarnDatabaseMigrationTest {
    @Test fun upgradePreservesSavedChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "upgrade-test.db"
        fun open() = Room.databaseBuilder(context, MobHarnDatabase::class.java, name)
            .addMigrations(MobHarnDatabase.MIGRATION_2_3, MobHarnDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        context.deleteDatabase(name)
        val db = open()
        val id = try {
            val chat = db.chatDao().insertSession(ChatSessionEntity(title = "Before upgrade"))
            db.chatDao().insertMessage(ChatMessageEntity(sessionId = chat, role = "user", content = "Keep me"))
            db.workspaceSnapshotDao().insertSnapshot(
                WorkspaceSnapshotEntity(
                    projectName = "starter_web",
                    relativePath = "legacy.txt",
                    previousContent = "old legacy",
                    newContent = "new legacy",
                    summary = "legacy edit",
                    timestamp = 42L
                )
            )
            chat
        } finally { db.close() }
        // Reconstruct the pseudo-v2 schema: drop v3 chat index and recreate v2/v3 workspace_snapshots without v4 columns.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX index_chat_messages_sessionId_timestamp_id")
            it.execSQL(
                """
                CREATE TABLE workspace_snapshots_v2 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    projectName TEXT NOT NULL,
                    relativePath TEXT NOT NULL,
                    previousContent TEXT NOT NULL,
                    newContent TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent()
            )
            it.execSQL(
                """
                INSERT INTO workspace_snapshots_v2 (id, projectName, relativePath, previousContent, newContent, summary, timestamp)
                SELECT id, projectName, relativePath, previousContent, newContent, summary, timestamp FROM workspace_snapshots
                """.trimIndent()
            )
            it.execSQL("DROP TABLE workspace_snapshots")
            it.execSQL("ALTER TABLE workspace_snapshots_v2 RENAME TO workspace_snapshots")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_snapshots_projectName ON workspace_snapshots (projectName)")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_snapshots_relativePath ON workspace_snapshots (relativePath)")
            it.version = 2
        }
        val upgraded = open()
        try {
            assertEquals("Keep me", upgraded.chatDao().getMessages(id).single().content)
            val snap = upgraded.workspaceSnapshotDao().getLatestSnapshot("starter_web", "legacy.txt")
            assertEquals("old legacy", snap?.previousContent)
            assertEquals("new legacy", snap?.newContent)
            assertNull(snap?.previousExisted)
            assertNull(snap?.previousBytes)
            assertNull(snap?.afterSha256)
        } finally { upgraded.close() }
    }
}
