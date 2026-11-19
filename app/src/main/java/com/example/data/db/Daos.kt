package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query(
        """
        SELECT m.* FROM chat_messages m
        JOIN chat_messages_fts f ON m.id = f.rowid
        WHERE chat_messages_fts MATCH :ftsQuery
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessagesFts(ftsQuery: String, limit: Int = 15): List<ChatMessageEntity>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE content LIKE '%' || :query || '%' OR thinking LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessagesFallback(query: String, limit: Int = 15): List<ChatMessageEntity>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_entries ORDER BY importance DESC, updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM memory_entries ORDER BY importance DESC, updatedAt DESC")
    suspend fun getAllMemories(): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries WHERE category = :category ORDER BY importance DESC, updatedAt DESC")
    suspend fun getMemoriesByCategory(category: String): List<MemoryEntryEntity>

    @Query(
        """
        SELECT * FROM memory_entries
        WHERE keyName LIKE '%' || :query || '%'
           OR content LIKE '%' || :query || '%'
           OR category LIKE '%' || :query || '%'
        ORDER BY importance DESC, updatedAt DESC
        """
    )
    suspend fun searchMemories(query: String): List<MemoryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(entry: MemoryEntryEntity): Long

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memory_entries WHERE keyName = :keyName")
    suspend fun deleteMemoryByKey(keyName: String): Int
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM agent_skills ORDER BY enabled DESC, usageCount DESC, title ASC")
    fun observeSkills(): Flow<List<AgentSkillEntity>>

    @Query("SELECT * FROM agent_skills WHERE enabled = 1 ORDER BY usageCount DESC, title ASC")
    suspend fun getEnabledSkills(): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE slug = :slug LIMIT 1")
    suspend fun getSkillBySlug(slug: String): AgentSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: AgentSkillEntity): Long

    @Query("UPDATE agent_skills SET usageCount = usageCount + 1, updatedAt = :now WHERE slug = :slug")
    suspend fun incrementUsage(slug: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE agent_skills SET enabled = :enabled WHERE id = :id")
    suspend fun setSkillEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM agent_skills WHERE id = :id")
    suspend fun deleteSkill(id: Long)
}

@Dao
interface WorkspaceSnapshotDao {
    @Query("SELECT * FROM workspace_snapshots WHERE projectName = :projectName ORDER BY timestamp DESC LIMIT 50")
    fun observeSnapshots(projectName: String): Flow<List<WorkspaceSnapshotEntity>>

    @Query("SELECT * FROM workspace_snapshots WHERE projectName = :projectName AND relativePath = :relativePath ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshot(projectName: String, relativePath: String): WorkspaceSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: WorkspaceSnapshotEntity): Long

    @Query("DELETE FROM workspace_snapshots WHERE id = :id")
    suspend fun deleteSnapshot(id: Long)
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_tasks ORDER BY enabled DESC, id DESC")
    fun observeTasks(): Flow<List<AutomationTaskEntity>>

    @Query("SELECT * FROM automation_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): AutomationTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AutomationTaskEntity): Long

    @Query("UPDATE automation_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE automation_tasks SET lastRunAt = :runAt, lastResult = :result WHERE id = :id")
    suspend fun updateRunResult(id: Long, runAt: Long, result: String)

    @Query("DELETE FROM automation_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)
}

@Dao
interface ModelProfileDao {
    @Query("SELECT * FROM model_profiles ORDER BY importedAt DESC")
    fun observeModels(): Flow<List<ModelProfileEntity>>

    @Query("SELECT * FROM model_profiles WHERE id = :id")
    suspend fun getModelById(id: Long): ModelProfileEntity?

    @Query("SELECT * FROM model_profiles ORDER BY importedAt DESC LIMIT 1")
    suspend fun getLatestModel(): ModelProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: ModelProfileEntity): Long

    @Update
    suspend fun updateModel(model: ModelProfileEntity)

    @Query("DELETE FROM model_profiles WHERE id = :id")
    suspend fun deleteModel(id: Long)
}

@Dao
interface ToolLogDao {
    @Query("SELECT * FROM tool_execution_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<ToolExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ToolExecutionLogEntity): Long

    @Query("DELETE FROM tool_execution_logs")
    suspend fun clearLogs()
}

@Dao
interface CustomToolDao {
    @Query("SELECT * FROM custom_tools ORDER BY enabled DESC, usageCount DESC, name ASC")
    fun observeCustomTools(): Flow<List<CustomToolEntity>>

    @Query("SELECT * FROM custom_tools WHERE enabled = 1 ORDER BY usageCount DESC, name ASC")
    suspend fun getEnabledCustomTools(): List<CustomToolEntity>

    @Query("SELECT * FROM custom_tools WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): CustomToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tool: CustomToolEntity): Long

    @Query("UPDATE custom_tools SET usageCount = usageCount + 1, updatedAt = :now WHERE name = :name")
    suspend fun incrementUsage(name: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE custom_tools SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM custom_tools WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM custom_tools WHERE name = :name")
    suspend fun deleteByName(name: String): Int
}
