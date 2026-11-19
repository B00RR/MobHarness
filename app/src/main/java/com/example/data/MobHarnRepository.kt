package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.agent.ContextAndMemoryEngine
import com.example.data.db.AgentSkillEntity
import com.example.data.db.AutomationTaskEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.db.CustomToolEntity
import com.example.data.db.MobHarnDatabase
import com.example.data.db.MemoryEntryEntity
import com.example.data.db.ModelProfileEntity
import com.example.data.db.ToolExecutionLogEntity
import com.example.data.db.WorkspaceSnapshotEntity
import com.example.data.prefs.AppPreferences
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import com.example.data.vault.SessionCheckpoint
import com.example.data.vault.VaultDocuments
import com.example.data.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class MobHarnRepository(
    private val context: Context,
    private val db: MobHarnDatabase,
    val vault: MarkdownVaultManager,
    val workspace: WorkspaceManager,
    val prefs: PreferencesRepository
) {
    val sessionsFlow: Flow<List<ChatSessionEntity>> = db.chatDao().observeSessions()
    val memoriesFlow: Flow<List<MemoryEntryEntity>> = db.memoryDao().observeAllMemories()
    val skillsFlow: Flow<List<AgentSkillEntity>> = db.skillDao().observeSkills()
    val automationsFlow: Flow<List<AutomationTaskEntity>> = db.automationDao().observeTasks()
    val modelsFlow: Flow<List<ModelProfileEntity>> = db.modelProfileDao().observeModels()
    val toolLogsFlow: Flow<List<ToolExecutionLogEntity>> = db.toolLogDao().observeRecentLogs()
    val customToolsFlow: Flow<List<CustomToolEntity>> = db.customToolDao().observeCustomTools()
    val preferencesFlow: Flow<AppPreferences> = prefs.preferences

    suspend fun bootstrapDefaults() = withContext(Dispatchers.IO) {
        workspace.ensureStarterProject()

        val existingSkills = db.skillDao().getEnabledSkills()
        if (existingSkills.isEmpty()) {
            DEFAULT_SKILLS.forEach { db.skillDao().upsertSkill(it) }
        }

        val existingCustomTools = db.customToolDao().getEnabledCustomTools()
        if (existingCustomTools.isEmpty()) {
            db.customToolDao().upsert(
                CustomToolEntity(
                    name = "text_stats",
                    description = "Analizza un testo e restituisce conteggio parole, caratteri, frasi e stima token",
                    parametersDoc = "inputJson: stringa di testo o oggetto {\"text\": \"...\"}",
                    jsCode = """
                        const raw = typeof INPUT === 'string' ? INPUT : (INPUT && INPUT.text ? String(INPUT.text) : JSON.stringify(INPUT || ''));
                        const words = raw.trim() ? raw.trim().split(/\s+/).length : 0;
                        const lines = raw ? raw.split('\n').length : 0;
                        const estTokens = Math.ceil(raw.length / 3.8);
                        return { chars: raw.length, words, lines, estimatedTokens: estTokens };
                    """.trimIndent(),
                    createdByAgent = false
                )
            )
        }

        val existingMemories = db.memoryDao().getAllMemories()
        if (existingMemories.isEmpty()) {
            db.memoryDao().upsertMemory(
                MemoryEntryEntity(
                    category = "USER_PROFILE",
                    keyName = "dispositivo_principale",
                    content = "Pixel 10 Pro XL con inferenza locale Gemma 4 LiteRT-LM",
                    importance = 5
                )
            )
            db.memoryDao().upsertMemory(
                MemoryEntryEntity(
                    category = "PREFERENCE",
                    keyName = "lingua_preferita",
                    content = "Italiano, risposte tecniche chiare e orientate all'azione",
                    importance = 4
                )
            )
        }

        val allMems = db.memoryDao().getAllMemories()
        val allSkills = db.skillDao().getEnabledSkills()
        vault.ensureInitialized(allMems, allSkills)

        val sessions = db.chatDao().observeSessions().first()
        if (sessions.isEmpty()) {
            db.chatDao().insertSession(
                ChatSessionEntity(
                    title = "Sessione #1",
                    activeProject = "starter_web"
                )
            )
        }
    }

    // --- Chat & FTS ---
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        db.chatDao().observeMessages(sessionId)

    suspend fun getMessages(
        sessionId: Long,
        afterMessageId: Long = 0L,
        recentLimit: Int = 20
    ): List<ChatMessageEntity> =
        if (afterMessageId <= 0L && recentLimit >= 1000) {
            db.chatDao().getMessages(sessionId)
        } else {
            db.chatDao().getMessages(sessionId, afterMessageId, recentLimit)
        }

    suspend fun getAllMessages(sessionId: Long): List<ChatMessageEntity> =
        db.chatDao().getMessages(sessionId)

    suspend fun createSession(title: String, projectName: String): Long =
        db.chatDao().insertSession(ChatSessionEntity(title = title, activeProject = projectName))

    suspend fun updateSession(session: ChatSessionEntity) = db.chatDao().updateSession(session)

    suspend fun deleteSession(sessionId: Long) {
        db.chatDao().deleteSession(sessionId)
        vault.deleteSessionDigest(sessionId)
    }

    suspend fun getSessionCheckpoint(sessionId: Long): SessionCheckpoint = vault.readSessionCheckpoint(sessionId)

    suspend fun getSessionDigest(sessionId: Long): String = vault.readSessionDigest(sessionId)

    suspend fun saveSessionDigest(
        sessionId: Long,
        digest: String,
        lastSummarizedMessageId: Long = 0L
    ) = vault.writeSessionDigest(sessionId, digest, lastSummarizedMessageId)

    suspend fun addMessage(message: ChatMessageEntity): Long = db.chatDao().insertMessage(message)

    suspend fun updateMessage(message: ChatMessageEntity) = db.chatDao().updateMessage(message)

    suspend fun searchSessionsHistory(query: String, limit: Int = 12): List<ChatMessageEntity> =
        withContext(Dispatchers.Default) {
            val ftsTerms = ContextAndMemoryEngine.buildFtsOrQuery(query)
            val fetchLimit = (limit * 3).coerceAtMost(48)
            val candidates = runCatching {
                if (ftsTerms.isNotBlank()) db.chatDao().searchMessagesFts(ftsTerms, fetchLimit)
                else db.chatDao().searchMessagesFallback(query.trim(), fetchLimit)
            }.getOrElse {
                db.chatDao().searchMessagesFallback(query.trim(), fetchLimit)
            }
            ContextAndMemoryEngine.rankPastMessages(query, candidates, limit)
        }

    // --- Memory & Vault ---
    suspend fun readVault(): VaultDocuments = vault.readVault()

    suspend fun upsertMemoryAndSync(
        category: String,
        keyName: String,
        content: String,
        importance: Int = 3
    ): Long {
        val cleanCat = category.uppercase().trim().ifBlank { "FACT" }
        val cleanKey = keyName.trim().lowercase().replace(Regex("\\s+"), "_")
        val id = db.memoryDao().upsertMemory(
            MemoryEntryEntity(
                category = cleanCat,
                keyName = cleanKey,
                content = content.trim(),
                importance = importance.coerceIn(1, 5),
                updatedAt = System.currentTimeMillis()
            )
        )
        vault.syncFromMemories(db.memoryDao().getAllMemories())
        return id
    }

    suspend fun deleteMemoryAndSync(id: Long) {
        db.memoryDao().deleteMemoryById(id)
        vault.syncFromMemories(db.memoryDao().getAllMemories())
    }

    suspend fun getAllMemories(): List<MemoryEntryEntity> = db.memoryDao().getAllMemories()

    suspend fun searchMemories(query: String): List<MemoryEntryEntity> = withContext(Dispatchers.Default) {
        val all = db.memoryDao().getAllMemories()
        if (all.isEmpty()) return@withContext emptyList()
        val ranked = ContextAndMemoryEngine.rankMemories(query, all, limit = 12)
        if (ranked.isNotEmpty()) ranked.map { it.entry }
        else db.memoryDao().searchMemories(query)
    }

    suspend fun saveSoulMd(content: String) = vault.writeSoulMd(content)
    suspend fun saveUserMd(content: String) = vault.writeUserMd(content)
    suspend fun saveMemoryMd(content: String) = vault.writeMemoryMd(content)

    // --- Skills ---
    suspend fun getEnabledSkills(): List<AgentSkillEntity> = db.skillDao().getEnabledSkills()

    suspend fun loadSkill(slug: String): AgentSkillEntity? {
        val clean = slug.trim().removePrefix("/").lowercase()
        val skill = db.skillDao().getSkillBySlug(clean) ?: return null
        db.skillDao().incrementUsage(clean)
        return skill
    }

    suspend fun upsertSkill(
        slug: String,
        title: String,
        description: String,
        instructionsMd: String
    ): Long {
        val cleanSlug = slug.trim().removePrefix("/").lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        val existing = db.skillDao().getSkillBySlug(cleanSlug)
        val id = db.skillDao().upsertSkill(
            AgentSkillEntity(
                id = existing?.id ?: 0,
                slug = cleanSlug,
                title = title.trim(),
                description = description.trim(),
                instructionsMd = instructionsMd.trim(),
                enabled = true,
                usageCount = existing?.usageCount ?: 0,
                updatedAt = System.currentTimeMillis()
            )
        )
        vault.syncSkillsToDisk(db.skillDao().getEnabledSkills())
        return id
    }

    suspend fun setSkillEnabled(id: Long, enabled: Boolean) = db.skillDao().setSkillEnabled(id, enabled)

    suspend fun deleteSkill(id: Long) = db.skillDao().deleteSkill(id)

    // --- Workspace Snapshots ---
    fun observeSnapshots(projectName: String): Flow<List<WorkspaceSnapshotEntity>> =
        db.workspaceSnapshotDao().observeSnapshots(projectName)

    suspend fun recordSnapshot(
        projectName: String,
        relativePath: String,
        previousContent: String,
        newContent: String,
        summary: String
    ) {
        db.workspaceSnapshotDao().insertSnapshot(
            WorkspaceSnapshotEntity(
                projectName = projectName,
                relativePath = relativePath,
                previousContent = previousContent,
                newContent = newContent,
                summary = summary
            )
        )
    }

    suspend fun rollbackLatestSnapshot(projectName: String, relativePath: String): Boolean {
        val latest = db.workspaceSnapshotDao().getLatestSnapshot(projectName, relativePath)
            ?: return false
        workspace.writeFile(projectName, relativePath, latest.previousContent)
        db.workspaceSnapshotDao().deleteSnapshot(latest.id)
        return true
    }

    // --- Automations ---
    suspend fun upsertAutomation(task: AutomationTaskEntity): Long = db.automationDao().upsertTask(task)
    suspend fun setAutomationEnabled(id: Long, enabled: Boolean) = db.automationDao().setEnabled(id, enabled)
    suspend fun updateAutomationResult(id: Long, result: String) =
        db.automationDao().updateRunResult(id, System.currentTimeMillis(), result)
    suspend fun deleteAutomation(id: Long) = db.automationDao().deleteTask(id)
    suspend fun getAutomationById(id: Long): AutomationTaskEntity? = db.automationDao().getTaskById(id)

    // --- Tool Logs ---
    suspend fun logToolExecution(
        sessionId: Long?,
        toolName: String,
        category: String,
        argumentsJson: String,
        resultPreview: String,
        durationMs: Long,
        success: Boolean
    ) {
        db.toolLogDao().insertLog(
            ToolExecutionLogEntity(
                sessionId = sessionId,
                toolName = toolName,
                category = category,
                argumentsJson = argumentsJson.take(400),
                resultPreview = resultPreview.take(500),
                durationMs = durationMs,
                success = success
            )
        )
        db.toolLogDao().pruneOldLogs()
    }

    suspend fun clearToolLogs() = db.toolLogDao().clearLogs()

    // --- Custom Self-Evolving JS Tools ---
    suspend fun getEnabledCustomTools(): List<CustomToolEntity> = db.customToolDao().getEnabledCustomTools()

    suspend fun getCustomToolByName(name: String): CustomToolEntity? =
        db.customToolDao().getByName(name.trim().lowercase())

    suspend fun upsertCustomTool(
        name: String,
        description: String,
        parametersDoc: String,
        jsCode: String,
        createdByAgent: Boolean = true
    ): Long {
        val cleanName = name.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_").take(40)
        return db.customToolDao().upsert(
            CustomToolEntity(
                name = cleanName,
                description = description.trim(),
                parametersDoc = parametersDoc.trim().ifBlank { "inputJson: JSON o stringa opzionale" },
                jsCode = jsCode.trim(),
                enabled = true,
                createdByAgent = createdByAgent,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun incrementCustomToolUsage(name: String) = db.customToolDao().incrementUsage(name.trim().lowercase())
    suspend fun setCustomToolEnabled(id: Long, enabled: Boolean) = db.customToolDao().setEnabled(id, enabled)
    suspend fun deleteCustomTool(id: Long) = db.customToolDao().deleteById(id)
    suspend fun deleteCustomToolByName(name: String): Int = db.customToolDao().deleteByName(name.trim().lowercase())

    // --- Model Profiles & SAF Import ---
    suspend fun getModelById(id: Long): ModelProfileEntity? = db.modelProfileDao().getModelById(id)
    suspend fun getActiveModelProfile(): ModelProfileEntity? {
        val activeId = prefs.preferences.first().activeModelId
        return if (activeId > 0L) db.modelProfileDao().getModelById(activeId) ?: db.modelProfileDao().getLatestModel()
        else db.modelProfileDao().getLatestModel()
    }
    suspend fun updateModelProfile(profile: ModelProfileEntity) = db.modelProfileDao().updateModel(profile)

    suspend fun deleteModelProfile(profile: ModelProfileEntity) = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(profile.filePath)
            if (file.exists() && file.parentFile?.name == "litertlm_models") {
                file.delete()
            }
        }
        db.modelProfileDao().deleteModel(profile.id)
    }

    suspend fun importModelFromUri(
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): Result<ModelProfileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var displayName = "gemma4_local.litertlm"
            var totalBytes = -1L

            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        totalBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

            importModelFromStream(
                displayName = displayName,
                totalBytes = totalBytes,
                openStream = { resolver.openInputStream(uri) },
                onProgress = onProgress
            ).getOrThrow()
        }
    }

    internal suspend fun importModelFromStream(
        displayName: String,
        totalBytes: Long,
        openStream: () -> InputStream?,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<ModelProfileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val modelsDir = File(context.filesDir, "litertlm_models").apply { mkdirs() }
            val safeFileName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destFile = File(modelsDir, safeFileName)
            val tempFile = File(
                modelsDir,
                "${safeFileName}.${System.currentTimeMillis()}.${UUID.randomUUID().toString().take(8)}.tmp"
            )

            onProgress(0.02f, "Copia di $displayName nello storage veloce del Pixel...")

            var copySucceeded = false
            try {
                val stream = openStream() ?: throw IllegalStateException("Impossibile aprire lo stream del file selezionato.")
                stream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(256 * 1024) // 256 KB chunk: fast UFS copy with 4x lower peak heap
                        var copied = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes > 0) {
                                val pct = (copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                val copiedMb = copied / (1024 * 1024)
                                val totalMb = totalBytes / (1024 * 1024)
                                onProgress(pct, "Copiati $copiedMb MB / $totalMb MB")
                            } else {
                                val copiedMb = copied / (1024 * 1024)
                                onProgress(0.5f, "Copiati $copiedMb MB...")
                            }
                        }
                        output.flush()
                    }
                }

                val finalSize = tempFile.length()
                if (totalBytes >= 0 && finalSize != totalBytes) {
                    throw IOException("Dimensione file incompleta: attesi $totalBytes byte, ricevuti $finalSize byte.")
                }
                if (finalSize == 0L) {
                    throw IOException("Il file importato è vuoto (0 byte).")
                }

                replaceAtomically(tempFile, destFile)
                copySucceeded = true
            } finally {
                if (!copySucceeded && tempFile.exists()) {
                    tempFile.delete()
                }
            }

            val finalSize = destFile.length()
            val cleanTitle = displayName
                .removeSuffix(".litertlm")
                .removeSuffix(".task")
                .replace('_', ' ')
                .replace('-', ' ')

            val npuLikely = displayName.contains("tensor", ignoreCase = true) ||
                displayName.contains("_g5", ignoreCase = true) ||
                displayName.contains("npu", ignoreCase = true)

            val existingProfile = db.modelProfileDao().getModelByPath(destFile.absolutePath)

            // Lean & cool defaults: text-only (saves ~500MB VRAM until user toggles Vision),
            // 6K context (fits dynamic tools with low KV-cache RAM), and Chain of Thought enabled with eco budget.
            val profile = ModelProfileEntity(
                id = existingProfile?.id ?: 0,
                displayName = cleanTitle,
                filePath = destFile.absolutePath,
                fileSizeBytes = finalSize,
                supportsVision = existingProfile?.supportsVision ?: false,
                preferredBackend = existingProfile?.preferredBackend ?: if (npuLikely) "NPU" else "GPU",
                maxNumTokens = existingProfile?.maxNumTokens ?: 6144,
                temperature = existingProfile?.temperature ?: 0.7f,
                topK = existingProfile?.topK ?: 64,
                topP = existingProfile?.topP ?: 0.95f,
                thinkingEnabled = existingProfile?.thinkingEnabled ?: true
            )
            val id = db.modelProfileDao().upsertModel(profile)
            val saved = profile.copy(id = id)
            prefs.setActiveModelId(id)
            onProgress(1f, "Modello importato con successo!")
            saved
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        // Same directory, POSIX rename on Android: replace in one step or leave the old file intact.
        if (!source.renameTo(target)) throw IOException("Impossibile sostituire il modello importato.")
    }

    companion object {
        private val DEFAULT_SKILLS = listOf(
            AgentSkillEntity(
                slug = "coding_workflow",
                title = "Coding Agent & Sandbox Loop",
                description = "Ciclo professionale leggi -> patcha -> esegui in sandbox JS/SQL -> correggi bug",
                instructionsMd = """
                    # Skill: Coding Workflow
                    1. Ispeziona prima il progetto con `list_workspace_files` e leggi le righe interessate con `read_file_lines`.
                    2. Per modifiche chirurgiche usa `patch_workspace_file`; per nuovi file usa `write_workspace_file`.
                    3. Se scrivi logica JavaScript ES6+ o algoritmi, esegui subito un test reale tramite `run_js_sandbox` verificando l'output e l'assenza di eccezioni.
                    4. Se lavori su schemi o query SQL, verifica con `run_sql_sandbox`.
                    5. Riassumi all'utente i file toccati, i test eseguiti e se è pronta la Live Preview in Studio.
                """.trimIndent()
            ),
            AgentSkillEntity(
                slug = "daily_briefing",
                title = "Briefing Quotidiano Pixel",
                description = "Riepilogo rapido di memoria recente, stato batteria/rete e attività salvate",
                instructionsMd = """
                    # Skill: Daily Briefing
                    1. Chiama `get_device_telemetry` per leggere stato batteria, memoria e connettività.
                    2. Chiama `search_memory` per richiamare progetti o promemoria aperti.
                    3. Presenta un briefing pulito in 3 sezioni: Stato Dispositivo, Priorità in Corso, Prossima Azione Consigliata.
                """.trimIndent()
            ),
            AgentSkillEntity(
                slug = "memory_curator",
                title = "Curatore Memoria Locale",
                description = "Estrae e consolida fatti duraturi in USER.md e MEMORY.md senza duplicati",
                instructionsMd = """
                    # Skill: Memory Curator
                    1. Usa `search_memory` per controllare le voci già presenti.
                    2. Aggiorna o salva fatti stabili (preferenze, stack tecnologico, progetti attivi) con `save_memory` usando chiavi chiare e importanza 1-5.
                    3. Evita di salvare dati effimeri o temporanei nella memoria globale.
                """.trimIndent()
            ),
            AgentSkillEntity(
                slug = "android_actions",
                title = "Azioni Rapide Android",
                description = "Imposta sveglie, crea eventi calendario, apri mappe o app sul Pixel",
                instructionsMd = """
                    # Skill: Android Actions
                    1. Estrai parametri precisi (orario HH:MM per sveglie, titolo/data per calendario, query per mappe).
                    2. Invoca lo strumento Android dedicato (`set_alarm`, `create_calendar_event`, `open_maps`, `launch_app`).
                    3. Conferma l'esito all'utente riportando i dettagli esatti eseguiti.
                """.trimIndent()
            )
        )
    }
}
