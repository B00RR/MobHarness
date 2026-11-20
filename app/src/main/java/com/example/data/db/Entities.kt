package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelName: String = "",
    val activeProject: String = "starter_web",
    val estimatedTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "timestamp", "id"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val thinking: String = "",
    val imagePath: String? = null,
    val toolCallsJson: String = "[]",
    val tokensPerSec: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Fts4(contentEntity = ChatMessageEntity::class)
@Entity(tableName = "chat_messages_fts")
data class ChatMessageFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val content: String,
    val thinking: String
)

@Entity(
    tableName = "memory_entries",
    indices = [Index(value = ["category", "keyName"], unique = true)]
)
data class MemoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // USER_PROFILE, FACT, PREFERENCE, PROJECT, CODING_CONVENTION
    val keyName: String,
    val content: String,
    val importance: Int = 3, // 1..5
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_skills",
    indices = [Index(value = ["slug"], unique = true)]
)
data class AgentSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slug: String,
    val title: String,
    val description: String,
    val instructionsMd: String,
    val usageCount: Int = 0,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "workspace_snapshots",
    indices = [Index("projectName"), Index("relativePath")]
)
data class WorkspaceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectName: String,
    val relativePath: String,
    val previousContent: String,
    val newContent: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis(),
    val previousExisted: Boolean? = null,
    val previousBytes: ByteArray? = null,
    val afterSha256: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkspaceSnapshotEntity) return false
        return id == other.id &&
            projectName == other.projectName &&
            relativePath == other.relativePath &&
            previousContent == other.previousContent &&
            newContent == other.newContent &&
            summary == other.summary &&
            timestamp == other.timestamp &&
            previousExisted == other.previousExisted &&
            previousBytes.contentEquals(other.previousBytes) &&
            afterSha256 == other.afterSha256
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + projectName.hashCode()
        result = 31 * result + relativePath.hashCode()
        result = 31 * result + previousContent.hashCode()
        result = 31 * result + newContent.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (previousExisted?.hashCode() ?: 0)
        result = 31 * result + (previousBytes?.contentHashCode() ?: 0)
        result = 31 * result + (afterSha256?.hashCode() ?: 0)
        return result
    }
}

@Entity(tableName = "automation_tasks")
data class AutomationTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val prompt: String,
    val actionType: String, // BRIEFING, TELEMETRY_CHECK, WEB_MONITOR, MEMORY_DIGEST, WORKSPACE_AUDIT
    val targetUrl: String = "",
    val intervalMinutes: Long = 60,
    val isPeriodic: Boolean = true,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String = "",
    val workTag: String
)

@Entity(
    tableName = "model_profiles",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class ModelProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val supportsVision: Boolean = true,
    val preferredBackend: String = "GPU", // GPU, CPU
    val maxNumTokens: Int = 8192,
    val temperature: Float = 0.7f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val thinkingEnabled: Boolean = true,
    val importedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tool_execution_logs",
    indices = [Index("timestamp")]
)
data class ToolExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val toolName: String,
    val category: String,
    val argumentsJson: String,
    val resultPreview: String,
    val durationMs: Long,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "custom_tools",
    indices = [Index(value = ["name"], unique = true)]
)
data class CustomToolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val parametersDoc: String = "inputJson: JSON o stringa opzionale",
    val jsCode: String,
    val usageCount: Int = 0,
    val enabled: Boolean = true,
    val createdByAgent: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
