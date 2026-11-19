package com.example.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MobHarnDatabaseMigrationTest {

    private lateinit var context: Context
    private val testDbName = "migration_test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        context.deleteDatabase("mobharn.db")
        context.deleteDatabase("hermes_edge.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        context.deleteDatabase("mobharn.db")
        context.deleteDatabase("hermes_edge.db")
    }

    @Test
    fun migrateFromVersion1To2_preservesAllPersonalData() = runBlocking {
        val dbPath = context.getDatabasePath(testDbName)
        dbPath.parentFile?.mkdirs()

        // 1. Create SQLite DB with version 1 and populate personal data
        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        rawDb.version = 1

        rawDb.execSQL(
            """
            CREATE TABLE `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `modelName` TEXT NOT NULL,
                `activeProject` TEXT NOT NULL,
                `estimatedTokens` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        rawDb.execSQL(
            """
            CREATE TABLE `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `thinking` TEXT NOT NULL,
                `imagePath` TEXT,
                `toolCallsJson` TEXT NOT NULL,
                `tokensPerSec` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        rawDb.execSQL(
            """
            CREATE TABLE `memory_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `category` TEXT NOT NULL,
                `keyName` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `importance` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE UNIQUE INDEX `index_memory_entries_category_keyName` ON `memory_entries` (`category`, `keyName`)")

        rawDb.execSQL(
            """
            CREATE TABLE `agent_skills` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `slug` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `instructionsMd` TEXT NOT NULL,
                `usageCount` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE UNIQUE INDEX `index_agent_skills_slug` ON `agent_skills` (`slug`)")

        rawDb.execSQL(
            """
            CREATE TABLE `model_profiles` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `displayName` TEXT NOT NULL,
                `filePath` TEXT NOT NULL,
                `fileSizeBytes` INTEGER NOT NULL,
                `supportsVision` INTEGER NOT NULL,
                `preferredBackend` TEXT NOT NULL,
                `maxNumTokens` INTEGER NOT NULL,
                `temperature` REAL NOT NULL,
                `topK` INTEGER NOT NULL,
                `topP` REAL NOT NULL,
                `thinkingEnabled` INTEGER NOT NULL,
                `importedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE UNIQUE INDEX `index_model_profiles_filePath` ON `model_profiles` (`filePath`)")

        rawDb.execSQL(
            """
            CREATE TABLE `automation_tasks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `actionType` TEXT NOT NULL,
                `targetUrl` TEXT NOT NULL,
                `intervalMinutes` INTEGER NOT NULL,
                `isPeriodic` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `lastRunAt` INTEGER,
                `lastResult` TEXT NOT NULL,
                `workTag` TEXT NOT NULL
            )
            """.trimIndent()
        )

        // Populate sample data
        val sessionValues = ContentValues().apply {
            put("id", 1L)
            put("title", "Conversazione Importante")
            put("modelName", "gemma4-2b")
            put("activeProject", "starter_web")
            put("estimatedTokens", 120)
            put("createdAt", 1000L)
            put("updatedAt", 2000L)
        }
        rawDb.insert("chat_sessions", null, sessionValues)

        val messageValues = ContentValues().apply {
            put("id", 1L)
            put("sessionId", 1L)
            put("role", "user")
            put("content", "Qual è la formula di Eulero?")
            put("thinking", "")
            put("toolCallsJson", "[]")
            put("tokensPerSec", 24.5f)
            put("timestamp", 1500L)
        }
        rawDb.insert("chat_messages", null, messageValues)

        val memoryValues = ContentValues().apply {
            put("id", 1L)
            put("category", "USER_PROFILE")
            put("keyName", "preferred_editor")
            put("content", "Android Studio / VS Code")
            put("importance", 5)
            put("updatedAt", 3000L)
        }
        rawDb.insert("memory_entries", null, memoryValues)

        val skillValues = ContentValues().apply {
            put("id", 1L)
            put("slug", "math_engine")
            put("title", "Math Engine")
            put("description", "Calcoli precisi")
            put("instructionsMd", "Usa la sintassi corretta")
            put("usageCount", 10)
            put("enabled", 1)
            put("updatedAt", 4000L)
        }
        rawDb.insert("agent_skills", null, skillValues)

        val modelValues = ContentValues().apply {
            put("id", 1L)
            put("displayName", "Gemma 4 Custom")
            put("filePath", "/data/models/gemma4.litertlm")
            put("fileSizeBytes", 2_000_000_000L)
            put("supportsVision", 1)
            put("preferredBackend", "NPU")
            put("maxNumTokens", 8192)
            put("temperature", 0.7f)
            put("topK", 64)
            put("topP", 0.95f)
            put("thinkingEnabled", 1)
            put("importedAt", 5000L)
        }
        rawDb.insert("model_profiles", null, modelValues)

        val taskValues = ContentValues().apply {
            put("id", 1L)
            put("name", "Morning Briefing")
            put("prompt", "Fornisci le ultime notizie")
            put("actionType", "BRIEFING")
            put("targetUrl", "")
            put("intervalMinutes", 1440L)
            put("isPeriodic", 1)
            put("enabled", 1)
            put("lastResult", "Success")
            put("workTag", "briefing")
        }
        rawDb.insert("automation_tasks", null, taskValues)

        rawDb.close()

        // 2. Open DB with MobHarnDatabase (version 2) with MIGRATION_1_2 registered
        val migratedDb = Room.databaseBuilder(
            context,
            MobHarnDatabase::class.java,
            testDbName
        )
            .addMigrations(MobHarnDatabase.MIGRATION_1_2)
            .build()

        // 3. Verify all personal data was preserved intact
        val sessions = migratedDb.chatDao().observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals("Conversazione Importante", sessions[0].title)

        val messages = migratedDb.chatDao().getMessagesForSession(1L)
        assertEquals(1, messages.size)
        assertEquals("Qual è la formula di Eulero?", messages[0].content)

        val memories = migratedDb.memoryDao().getAllMemories()
        assertEquals(1, memories.size)
        assertEquals("preferred_editor", memories[0].keyName)
        assertEquals("Android Studio / VS Code", memories[0].content)

        val skill = migratedDb.skillDao().getSkillBySlug("math_engine")
        assertNotNull(skill)
        assertEquals("Math Engine", skill?.title)

        val model = migratedDb.modelProfileDao().getModelById(1L)
        assertNotNull(model)
        assertEquals("/data/models/gemma4.litertlm", model?.filePath)

        val task = migratedDb.automationDao().getTaskById(1L)
        assertNotNull(task)
        assertEquals("Morning Briefing", task?.name)

        // 4. Verify version 2 tables are created and functional
        val customToolId = migratedDb.customToolDao().upsert(
            CustomToolEntity(
                name = "test_tool",
                description = "Tool di test migrazione",
                parametersJson = "{}",
                scriptBody = "return 42;"
            )
        )
        assertTrue(customToolId > 0)
        val fetchedTool = migratedDb.customToolDao().getByName("test_tool")
        assertNotNull(fetchedTool)
        assertEquals("Tool di test migrazione", fetchedTool?.description)

        val logId = migratedDb.toolLogDao().insertLog(
            ToolExecutionLogEntity(
                toolName = "test_tool",
                argumentsJson = "{}",
                resultSnippet = "42",
                isError = false,
                durationMs = 15L
            )
        )
        assertTrue(logId > 0)

        val snapshotId = migratedDb.workspaceSnapshotDao().insertSnapshot(
            WorkspaceSnapshotEntity(
                projectName = "test_project",
                relativePath = "main.py",
                previousContent = "",
                newContent = "print('hello')",
                summary = "Initial commit"
            )
        )
        assertTrue(snapshotId > 0)

        migratedDb.close()
    }

    @Test
    fun legacyHermesDatabaseMigration_copiesFileAndUpgrades() = runBlocking {
        val oldDbFile = context.getDatabasePath("hermes_edge.db")
        oldDbFile.parentFile?.mkdirs()

        // Create a legacy hermes_edge.db at version 1
        val rawDb = SQLiteDatabase.openOrCreateDatabase(oldDbFile, null)
        rawDb.version = 1
        rawDb.execSQL(
            """
            CREATE TABLE `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `modelName` TEXT NOT NULL,
                `activeProject` TEXT NOT NULL,
                `estimatedTokens` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        val sessionValues = ContentValues().apply {
            put("id", 42L)
            put("title", "Sessione da Hermes Edge")
            put("modelName", "gemma4")
            put("activeProject", "p1")
            put("estimatedTokens", 50)
            put("createdAt", 100L)
            put("updatedAt", 200L)
        }
        rawDb.insert("chat_sessions", null, sessionValues)
        rawDb.close()

        // Get instance through MobHarnDatabase.getInstance()
        val db = MobHarnDatabase.getInstance(context)
        val sessions = db.chatDao().observeSessions().first()

        assertTrue(sessions.any { it.id == 42L && it.title == "Sessione da Hermes Edge" })
        db.close()
    }
}
