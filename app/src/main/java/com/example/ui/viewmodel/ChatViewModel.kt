package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.ContextAndMemoryEngine
import com.example.agent.EngineStatus
import com.example.agent.ThermalAndMemoryGuard
import com.example.agent.tools.PendingToolApproval
import com.example.agent.tools.ToolCallRecoveryParser
import com.example.agent.tools.ToolCallTrace
import com.example.agent.tools.encodeToolTracesToJson
import com.example.data.db.AgentSkillEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.prefs.AppPreferences
import com.example.data.prefs.ToolGroup
import com.example.di.MobHarnAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatUiState(
    val activeSessionId: Long = -1L,
    val isGenerating: Boolean = false,
    val streamingVisibleText: String = "",
    val streamingThinkingText: String = "",
    val liveToolTraces: List<ToolCallTrace> = emptyList(),
    val liveTokensPerSec: Float = 0f,
    val liveTtftMs: Long = 0L,
    val liveThermalNote: String? = null,
    val attachedImagePath: String? = null,
    val searchQuery: String = "",
    val searchResults: List<ChatMessageEntity> = emptyList(),
    val quickSandboxBanner: String? = null,
    val estimatedMemoryTokens: Int = 0,
    val generationStartedAtMs: Long = 0L,
    val thinkingDurationSec: Int = 0,
    val contextTokensUsed: Int = 0,
    val contextTokensMax: Int = 8192,
    val hasSessionDigest: Boolean = false,
    val autoCompactedTurnCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val appContext: Context,
    private val container: MobHarnAppContainer
) : ViewModel() {

    private val repo = container.repository
    private val runtime = container.runtimeManager

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Dedicated lightweight flow so MobHarnApp never recomposes on streaming tokens
    private val _incomingSharedPrompt = MutableStateFlow<String?>(null)
    val incomingSharedPrompt: StateFlow<String?> = _incomingSharedPrompt.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val sessions: StateFlow<List<ChatSessionEntity>> = repo.sessionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skills: StateFlow<List<AgentSkillEntity>> = repo.skillsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<AppPreferences> = repo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val engineStatus: StateFlow<EngineStatus> = runtime.status
    val lastInference = runtime.lastInference

    val pendingApproval: StateFlow<PendingToolApproval?> = container.approvalCoordinator.pendingApproval

    // Only re-query Room when activeSessionId actually changes — NEVER on streaming token ticks!
    val activeMessages: StateFlow<List<ChatMessageEntity>> = _uiState
        .map { it.activeSessionId }
        .distinctUntilChanged()
        .flatMapLatest { sid ->
            if (sid > 0L) repo.observeMessages(sid)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            repo.bootstrapDefaults()
            refreshMemoryTokenEstimate()
        }
        viewModelScope.launch {
            sessions.collect { list ->
                if (list.isNotEmpty() && _uiState.value.activeSessionId <= 0L) {
                    val firstSession = list.first()
                    val hasDigest = repo.getSessionDigest(firstSession.id).isNotBlank()
                    _uiState.update {
                        it.copy(
                            activeSessionId = firstSession.id,
                            contextTokensUsed = firstSession.estimatedTokens,
                            hasSessionDigest = hasDigest
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            repo.memoriesFlow
                .map { it.size }
                .distinctUntilChanged()
                .collect {
                    refreshMemoryTokenEstimate()
                }
        }
    }

    private suspend fun refreshMemoryTokenEstimate() {
        val docs = repo.readVault()
        _uiState.update { it.copy(estimatedMemoryTokens = docs.estimatedSystemTokens) }
    }

    fun selectSession(sessionId: Long) {
        if (sessionId == _uiState.value.activeSessionId) return
        stopGeneration()
        val targetSession = sessions.value.firstOrNull { it.id == sessionId }
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                streamingVisibleText = "",
                streamingThinkingText = "",
                liveToolTraces = emptyList(),
                liveThermalNote = null,
                generationStartedAtMs = 0L,
                thinkingDurationSec = 0,
                contextTokensUsed = targetSession?.estimatedTokens ?: 0,
                hasSessionDigest = false,
                autoCompactedTurnCount = 0
            )
        }
        viewModelScope.launch {
            val hasDigest = repo.getSessionDigest(sessionId).isNotBlank()
            _uiState.update { it.copy(hasSessionDigest = hasDigest) }
            runtime.resetConversation()
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val activeProject = preferences.value.activeProjectName
            val nextNum = sessions.value.size + 1
            val id = repo.createSession("Sessione #$nextNum", activeProject)
            _uiState.update {
                it.copy(
                    activeSessionId = id,
                    streamingVisibleText = "",
                    streamingThinkingText = "",
                    liveToolTraces = emptyList(),
                    liveThermalNote = null,
                    attachedImagePath = null,
                    generationStartedAtMs = 0L,
                    thinkingDurationSec = 0,
                    contextTokensUsed = 0,
                    hasSessionDigest = false,
                    autoCompactedTurnCount = 0
                )
            }
            runtime.resetConversation()
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repo.deleteSession(sessionId)
            if (_uiState.value.activeSessionId == sessionId) {
                val remaining = sessions.value.filterNot { it.id == sessionId }
                if (remaining.isNotEmpty()) {
                    _uiState.update { it.copy(activeSessionId = remaining.first().id) }
                } else {
                    createNewSession()
                }
            }
        }
    }

    fun searchHistory(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val results = if (query.isBlank()) emptyList() else repo.searchSessionsHistory(query)
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val dir = File(appContext.cacheDir, "chat_images").apply { mkdirs() }
                val target = File(dir, "img_${System.currentTimeMillis()}.jpg")
                // Downscale on decode so 50MP photos never expand full-res into RAM
                val ok = ThermalAndMemoryGuard.decodeAndSaveCompressedImage(
                    context = appContext,
                    sourceUri = uri,
                    destFile = target,
                    maxDimensionPx = 1024,
                    jpegQuality = 82
                )
                if (ok) target.absolutePath else null
            }
            if (path != null) {
                _uiState.update { it.copy(attachedImagePath = path) }
            } else {
                _events.tryEmit("Impossibile leggere l'immagine selezionata.")
            }
        }
    }

    fun attachCapturedImageFile(filePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ThermalAndMemoryGuard.compressImageFileInPlace(File(filePath), maxDimensionPx = 1024)
            }
            _uiState.update { it.copy(attachedImagePath = filePath) }
        }
    }

    fun clearAttachedImage() {
        _uiState.update { it.copy(attachedImagePath = null) }
    }

    fun receiveSharedIntent(sharedText: String?, sharedImageUri: Uri?) {
        if (sharedImageUri != null) {
            attachImageFromUri(sharedImageUri)
        }
        val prompt = when {
            !sharedText.isNullOrBlank() -> sharedText.trim()
            sharedImageUri != null -> "Analizza questo screenshot/immagine condivisa."
            else -> null
        }
        if (prompt != null) {
            _incomingSharedPrompt.value = prompt
            _events.tryEmit("Contenuto condiviso ricevuto in MobHarn!")
        }
    }

    fun consumeSharedPrompt() {
        _incomingSharedPrompt.value = null
    }

    fun resolveToolApproval(id: String, approved: Boolean, remember: Boolean = false) {
        container.approvalCoordinator.resolve(id, approved, remember)
    }

    fun dismissQuickSandboxBanner() {
        _uiState.update { it.copy(quickSandboxBanner = null) }
    }

    fun saveCodeBlockToWorkspace(language: String, code: String) {
        viewModelScope.launch {
            val ext = when (language.lowercase()) {
                "kotlin", "kt" -> "kt"
                "python", "py" -> "py"
                "javascript", "js" -> "js"
                "typescript", "ts" -> "ts"
                "html" -> "html"
                "css" -> "css"
                "sql" -> "sql"
                "json" -> "json"
                else -> "txt"
            }
            val project = preferences.value.activeProjectName
            val fileName = if (ext == "html") "index.html" else "snippet_${System.currentTimeMillis() % 10000}.$ext"
            val result = repo.workspace.writeFile(project, fileName, code)
            result.onSuccess { prev ->
                repo.recordSnapshot(project, fileName, prev, code, "Salvato dalla chat")
                _events.tryEmit("Salvato in Studio → $project/$fileName")
            }.onFailure {
                _events.tryEmit("Errore salvataggio: ${it.message}")
            }
        }
    }

    fun runCodeBlockInSandbox(language: String, code: String) {
        viewModelScope.launch {
            val lang = language.lowercase()
            val res = when (lang) {
                "sql", "sqlite" -> container.codeSandbox.runSql(code)
                "json" -> container.codeSandbox.validateJsonOrRegex("json", code)
                else -> container.codeSandbox.runJavaScript(code)
            }
            val prefix = if (res.success) "Sandbox OK (${res.durationMs}ms)" else "Sandbox Errore"
            _uiState.update { it.copy(quickSandboxBanner = "$prefix:\n${res.output.take(400)}") }
        }
    }

    fun compactCurrentSession() {
        val sessionId = _uiState.value.activeSessionId
        if (sessionId <= 0L) return
        viewModelScope.launch {
            val activeMaxCtx = (engineStatus.value as? EngineStatus.Ready)?.model?.maxNumTokens
                ?: _uiState.value.contextTokensMax
            val checkpoint = repo.getSessionCheckpoint(sessionId)
            val hotTurns = ContextAndMemoryEngine.getHotTurnCount(activeMaxCtx)
            val msgs = repo.getMessages(
                sessionId = sessionId,
                afterMessageId = checkpoint.lastSummarizedMessageId,
                recentLimit = hotTurns + 4
            )
            val existingDigest = checkpoint.digest
            val plan = withContext(Dispatchers.Default) {
                ContextAndMemoryEngine.buildContextWindowPlan(
                    allPriorMessages = msgs,
                    existingDigest = existingDigest,
                    maxContextTokens = activeMaxCtx,
                    forceCompaction = true,
                    lastSummarizedMessageId = checkpoint.lastSummarizedMessageId
                )
            }

            if (plan.updatedSessionDigest.isNotBlank()) {
                repo.saveSessionDigest(sessionId, plan.updatedSessionDigest, plan.lastSummarizedMessageId)
            }
            if (msgs.size >= 4 && plan.updatedSessionDigest.isNotBlank()) {
                val firstLine = plan.updatedSessionDigest
                    .lines()
                    .firstOrNull { it.startsWith("- ") }
                    ?.removePrefix("- ")
                    ?.take(180)
                    ?: plan.updatedSessionDigest.take(180)
                repo.upsertMemoryAndSync(
                    category = "PROJECT",
                    keyName = "riepilogo_sessione_$sessionId",
                    content = firstLine,
                    importance = 3
                )
            }

            runtime.resetConversation()
            sessions.value.firstOrNull { it.id == sessionId }?.let { session ->
                repo.updateSession(
                    session.copy(
                        estimatedTokens = plan.estimatedPromptTokens,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            _uiState.update {
                it.copy(
                    contextTokensUsed = plan.estimatedPromptTokens,
                    contextTokensMax = plan.maxContextTokens,
                    hasSessionDigest = plan.updatedSessionDigest.isNotBlank(),
                    autoCompactedTurnCount = plan.compactedTurnCount
                )
            }
            _events.tryEmit(
                if (plan.compactedTurnCount > 0) {
                    "Contesto compattato: ${plan.compactedTurnCount} turni sintetizzati nel checkpoint di sessione."
                } else {
                    "Cache KV liberata e pronta per nuovi turni."
                }
            )
        }
    }

    fun toggleThinkingForActiveModel() {
        viewModelScope.launch {
            val current = (engineStatus.value as? EngineStatus.Ready)?.model
                ?: repo.getActiveModelProfile()
            if (current == null) {
                _events.tryEmit("Nessun modello attivo. Caricane uno nella scheda Modello.")
                return@launch
            }
            val updated = current.copy(thinkingEnabled = !current.thinkingEnabled)
            repo.updateModelProfile(updated)
            runtime.setLoadedProfileThinking(updated.thinkingEnabled)
            val label = if (updated.thinkingEnabled) {
                "Ragionamento (Chain of Thought) attivato"
            } else {
                "Ragionamento (Chain of Thought) disattivato"
            }
            _events.tryEmit(label)
        }
    }

    fun sendMessage(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        val imagePath = _uiState.value.attachedImagePath
        if (prompt.isBlank() && imagePath == null) return
        if (_uiState.value.isGenerating) return

        val currentPrefs = preferences.value
        val currentStatus = engineStatus.value
        if (currentStatus !is EngineStatus.Ready && !runtime.canAutoReload()) {
            _events.tryEmit("Nessun modello Gemma 4 caricato. Vai nella scheda Modello e importa/carica il tuo .litertlm.")
            return
        }

        generationJob = viewModelScope.launch {
            // Transparently reload if model was auto-unloaded in background to save RAM
            if (engineStatus.value !is EngineStatus.Ready && runtime.canAutoReload()) {
                _events.tryEmit("Ricaricamento automatico del modello in memoria...")
                val reloaded = runtime.autoReloadIfNeeded(ecoThermalMode = currentPrefs.ecoThermalMode)
                if (!reloaded) {
                    _events.tryEmit("Impossibile ricaricare il modello. Ricaricalo dalla scheda Modello.")
                    return@launch
                }
            }

            val ready = engineStatus.value as? EngineStatus.Ready ?: return@launch

            var sessionId = _uiState.value.activeSessionId
            if (sessionId <= 0L) {
                sessionId = repo.createSession(
                    title = prompt.take(36).ifBlank { "Analisi Immagine" },
                    projectName = currentPrefs.activeProjectName
                )
                _uiState.update { it.copy(activeSessionId = sessionId) }
            }

            repo.addMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "user",
                    content = prompt.ifBlank { "Analizza questa immagine." },
                    imagePath = imagePath
                )
            )

            val existingSession = sessions.value.firstOrNull { it.id == sessionId }
            if (existingSession != null && existingSession.title.startsWith("Sessione")) {
                repo.updateSession(
                    existingSession.copy(
                        title = prompt.take(42).ifBlank { "Sessione Visione" },
                        modelName = ready.model.displayName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            val traces = mutableListOf<ToolCallTrace>()
            container.toolMonitor.activeSessionId = sessionId
            container.toolMonitor.activeProjectName = currentPrefs.activeProjectName
            container.toolMonitor.requireApprovalForExternal = currentPrefs.requireApprovalForExternalActions
            container.toolMonitor.onLiveTraceUpdate = { trace ->
                synchronized(traces) {
                    val idx = traces.indexOfFirst { it.id == trace.id }
                    if (idx >= 0) traces[idx] = trace else traces.add(trace)
                    _uiState.update { it.copy(liveToolTraces = traces.toList()) }
                }
            }

            val startedAtMs = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingVisibleText = "",
                    streamingThinkingText = "",
                    liveToolTraces = emptyList(),
                    liveTokensPerSec = 0f,
                    liveTtftMs = 0L,
                    liveThermalNote = null,
                    attachedImagePath = null,
                    generationStartedAtMs = startedAtMs,
                    thinkingDurationSec = 0
                )
            }

            val visibleBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var finalTps = 0f
            var lastUiPushMs = 0L
            var thinkingStartMs = 0L
            var thinkingDoneSec = 0
            var latestContextUsed = _uiState.value.contextTokensUsed
            val runtimeMaxCtx = runtime.getActiveContextStats().second
            var latestContextMax = (if (runtimeMaxCtx > 0) runtimeMaxCtx else ready.model.maxNumTokens).coerceIn(2048, 16384)

            try {
                val routedGroups = container.toolkitRegistry.selectRoutedToolGroups(
                    userPrompt = prompt,
                    enabledGroups = currentPrefs.enabledToolGroups
                )
                val checkpoint = repo.getSessionCheckpoint(sessionId)
                val hotTurns = ContextAndMemoryEngine.getHotTurnCount(latestContextMax)
                val allPriorMessages = repo.getMessages(
                    sessionId = sessionId,
                    afterMessageId = checkpoint.lastSummarizedMessageId,
                    recentLimit = hotTurns + 4
                ).dropLast(1)
                val existingDigest = checkpoint.digest

                // Mobile 3-tier context plan: keeps verbatim hot turns + auto-compacts overflow into rolling checkpoint
                val contextPlan = withContext(Dispatchers.Default) {
                    ContextAndMemoryEngine.buildContextWindowPlan(
                        allPriorMessages = allPriorMessages,
                        existingDigest = existingDigest,
                        maxContextTokens = latestContextMax,
                        lastSummarizedMessageId = checkpoint.lastSummarizedMessageId
                    )
                }

                val digestChanged = contextPlan.didAutoCompact &&
                    contextPlan.updatedSessionDigest.isNotBlank() &&
                    (contextPlan.updatedSessionDigest != existingDigest || contextPlan.lastSummarizedMessageId != checkpoint.lastSummarizedMessageId)
                if (digestChanged) {
                    repo.saveSessionDigest(sessionId, contextPlan.updatedSessionDigest, contextPlan.lastSummarizedMessageId)
                    _uiState.update {
                        it.copy(
                            hasSessionDigest = true,
                            autoCompactedTurnCount = contextPlan.compactedTurnCount,
                            contextTokensUsed = contextPlan.estimatedPromptTokens,
                            contextTokensMax = contextPlan.maxContextTokens
                        )
                    }
                    _events.tryEmit("Auto-compattamento: ${contextPlan.compactedTurnCount} turni sintetizzati per mantenere il contesto fluido.")
                } else {
                    _uiState.update {
                        it.copy(
                            hasSessionDigest = contextPlan.updatedSessionDigest.isNotBlank(),
                            contextTokensUsed = maxOf(it.contextTokensUsed, contextPlan.estimatedPromptTokens),
                            contextTokensMax = contextPlan.maxContextTokens
                        )
                    }
                }

                val systemPrompt = container.promptAssembler.assemble(
                    activeProject = currentPrefs.activeProjectName,
                    routedGroups = routedGroups,
                    recentContextDigest = contextPlan.updatedSessionDigest,
                    userPrompt = prompt,
                    contextBudgetTokens = latestContextMax
                )
                runtime.streamTurn(
                    sessionId = sessionId,
                    systemInstruction = systemPrompt,
                    userPrompt = prompt,
                    imageFilePath = imagePath,
                    enabledToolGroups = routedGroups,
                    recentHistory = contextPlan.hotHistoryPairs,
                    ecoThermalMode = currentPrefs.ecoThermalMode,
                    perMessageCharLimit = contextPlan.perMessageCharLimit,
                    maxSeedMessages = contextPlan.hotHistoryPairs.size.coerceAtLeast(4),
                    forceConversationReset = digestChanged
                ).collect { chunk ->
                    val now = SystemClock.elapsedRealtime()
                    if (chunk.thinkingDelta.isNotEmpty()) {
                        if (thinkingStartMs == 0L) thinkingStartMs = now
                        thinkingBuilder.append(chunk.thinkingDelta)
                    }
                    if (chunk.visibleDelta.isNotEmpty()) {
                        if (thinkingStartMs > 0L && thinkingDoneSec == 0) {
                            thinkingDoneSec = ((now - thinkingStartMs) / 1000L).toInt().coerceAtLeast(1)
                        }
                        visibleBuilder.append(chunk.visibleDelta)
                    }
                    finalTps = chunk.tokensPerSec
                    if (chunk.contextTokensUsed > 0) latestContextUsed = chunk.contextTokensUsed
                    if (chunk.contextTokensMax > 0) latestContextMax = chunk.contextTokensMax

                    // In background, slow down UI state pushes to ~2.8Hz so Compose does zero off-screen work
                    val uiIntervalMs = if (runtime.isAppInBackground) 350L else 95L
                    if (lastUiPushMs == 0L || now - lastUiPushMs >= uiIntervalMs) {
                        lastUiPushMs = now
                        val visSnap = visibleBuilder.toString()
                        val thinkSnap = thinkingBuilder.toString()
                        val currentThinkSec = when {
                            thinkingDoneSec > 0 -> thinkingDoneSec
                            thinkingStartMs > 0L -> ((now - thinkingStartMs) / 1000L).toInt().coerceAtLeast(1)
                            else -> 0
                        }
                        _uiState.update {
                            it.copy(
                                streamingVisibleText = visSnap,
                                streamingThinkingText = thinkSnap,
                                liveTokensPerSec = chunk.tokensPerSec,
                                liveTtftMs = chunk.ttftMs,
                                liveThermalNote = chunk.thermalNote,
                                thinkingDurationSec = currentThinkSec,
                                contextTokensUsed = latestContextUsed,
                                contextTokensMax = latestContextMax
                            )
                        }
                    }
                }

                val cleanedVisible = ToolCallRecoveryParser.stripRawToolCallMarkup(visibleBuilder.toString()).trim()
                val finalText = cleanedVisible.ifBlank {
                    val lastGoodTrace = traces.lastOrNull { it.status == "success" && it.resultSummary.isNotBlank() }
                    if (lastGoodTrace != null) {
                        "Ecco i dati raccolti da ${lastGoodTrace.toolName}:\n\n${lastGoodTrace.resultSummary}"
                    } else if (traces.isNotEmpty()) {
                        "Ho eseguito gli strumenti richiesti, ma non sono stati restituiti dati utili per rispondere."
                    } else {
                        "(risposta vuota)"
                    }
                }

                // Continuous-learning safety net: if the user explicitly asked to remember/note a fact
                // or learn/create a skill and the model forgot to invoke save_memory/save_skill, capture it!
                captureExplicitLearningIfMissed(
                    sessionId = sessionId,
                    userPrompt = prompt,
                    assistantReply = finalText,
                    traces = traces
                )

                repo.addMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = finalText,
                        thinking = thinkingBuilder.toString().trim(),
                        toolCallsJson = encodeToolTracesToJson(traces),
                        tokensPerSec = finalTps
                    )
                )
                sessions.value.firstOrNull { it.id == sessionId }?.let { session ->
                    repo.updateSession(
                        session.copy(
                            estimatedTokens = latestContextUsed.coerceAtLeast(contextPlan.estimatedPromptTokens),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                _uiState.update {
                    it.copy(
                        contextTokensUsed = latestContextUsed.coerceAtLeast(contextPlan.estimatedPromptTokens),
                        contextTokensMax = latestContextMax
                    )
                }
                refreshMemoryTokenEstimate()
            } catch (t: Throwable) {
                val partial = ToolCallRecoveryParser.stripRawToolCallMarkup(visibleBuilder.toString()).trim()
                val errText = if (partial.isNotBlank()) {
                    "$partial\n\n_[Generazione interrotta: ${t.message}]_"
                } else {
                    "Errore durante l'inferenza LiteRT-LM: ${t.message}"
                }
                repo.addMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = errText,
                        thinking = thinkingBuilder.toString().trim(),
                        toolCallsJson = encodeToolTracesToJson(traces),
                        tokensPerSec = finalTps
                    )
                )
            } finally {
                container.toolMonitor.onLiveTraceUpdate = null
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingVisibleText = "",
                        streamingThinkingText = "",
                        liveToolTraces = emptyList(),
                        liveThermalNote = null,
                        generationStartedAtMs = 0L,
                        thinkingDurationSec = 0
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        pendingApproval.value?.let { container.approvalCoordinator.resolve(it.id, false) }
        runtime.cancelGeneration()
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isGenerating = false,
                liveThermalNote = null,
                generationStartedAtMs = 0L,
                thinkingDurationSec = 0
            )
        }
    }

    /**
     * Zero-inference-cost continuous learning safety net:
     * Ensures explicit user memory notes ("segna che...", "ricordati che...", "mi chiamo...", "abito a...")
     * and explicit skill creation requests ("crea una skill...", "impara questa procedura...") are ALWAYS
     * persisted in Room + Markdown Vault even if the 4-bit model answered conversationally without calling the tool.
     */
    private suspend fun captureExplicitLearningIfMissed(
        sessionId: Long,
        userPrompt: String,
        assistantReply: String,
        traces: MutableList<ToolCallTrace>
    ) {
        val lower = userPrompt.lowercase().trim()
        if (lower.length < 8) return

        val alreadySavedMemory = synchronized(traces) {
            traces.any { it.toolName == "save_memory" && it.status == "success" }
        }
        val alreadySavedSkill = synchronized(traces) {
            traces.any { it.toolName == "save_skill" && it.status == "success" }
        }

        // 1. Explicit memory / note / personal fact capture
        if (!alreadySavedMemory) {
            val explicitMemoryPrefixes = listOf(
                Regex("""(?:ricordati|ricorda|segna|segnati|annota|tieni a mente|memorizza|salva|non dimenticare)\s+(?:che\s+|di\s+)?(.+)""", RegexOption.IGNORE_CASE)
            )
            val profilePatterns = listOf(
                "nome_utente" to Regex("""mi chiamo\s+([A-Za-zÀ-ÿ'\-\s]{2,32})""", RegexOption.IGNORE_CASE),
                "citta_utente" to Regex("""(?:abito|vivo)\s+(?:a|in)\s+([A-Za-zÀ-ÿ0-9'()\-,\s]{2,42})""", RegexOption.IGNORE_CASE),
                "lavoro_utente" to Regex("""(?:lavoro come|faccio il|faccio la|sono un|sono una)\s+([A-Za-zÀ-ÿ0-9'\-\s]{3,48})""", RegexOption.IGNORE_CASE),
                "preferenza_utente" to Regex("""preferisco\s+(?:sempre\s+)?(.{4,120})""", RegexOption.IGNORE_CASE)
            )

            val explicitFact = explicitMemoryPrefixes.firstNotNullOfOrNull { rx ->
                rx.find(userPrompt)?.groupValues?.getOrNull(1)?.trim()
            }?.take(220)

            if (!explicitFact.isNullOrBlank()) {
                val category = if (lower.contains("preferisc") || lower.contains("rispond") || lower.contains("stile")) {
                    "PREFERENCE"
                } else {
                    "FACT"
                }
                val slugKey = explicitFact.lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .take(28)
                    .ifBlank { "nota_${System.currentTimeMillis() % 10000}" }
                repo.upsertMemoryAndSync(category, slugKey, explicitFact, importance = 4)
                val trace = ToolCallTrace(
                    toolName = "save_memory",
                    category = ToolGroup.MEMORY.id,
                    argumentsSummary = "[$category] $slugKey = ${explicitFact.take(70)}",
                    resultSummary = "Salvato automaticamente in Memoria e sincronizzato su Markdown.",
                    durationMs = 4,
                    status = "success"
                )
                synchronized(traces) { traces.add(trace) }
                repo.logToolExecution(sessionId, "save_memory", ToolGroup.MEMORY.id, trace.argumentsSummary, trace.resultSummary, 4, true)
            } else {
                for ((key, rx) in profilePatterns) {
                    val matched = rx.find(userPrompt)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                    if (!matched.isNullOrBlank() && matched.length >= 2) {
                        val cat = if (key == "preferenza_utente") "PREFERENCE" else "USER_PROFILE"
                        repo.upsertMemoryAndSync(cat, key, matched, importance = 4)
                        val trace = ToolCallTrace(
                            toolName = "save_memory",
                            category = ToolGroup.MEMORY.id,
                            argumentsSummary = "[$cat] $key = ${matched.take(70)}",
                            resultSummary = "Profilo aggiornato in USER.md / MEMORY.md.",
                            durationMs = 4,
                            status = "success"
                        )
                        synchronized(traces) { traces.add(trace) }
                        repo.logToolExecution(sessionId, "save_memory", ToolGroup.MEMORY.id, trace.argumentsSummary, trace.resultSummary, 4, true)
                        break
                    }
                }
            }
        }

        // 2. Explicit skill creation capture
        if (!alreadySavedSkill && assistantReply.length >= 60) {
            val skillTrigger = listOf(
                "crea una skill", "salva come skill", "salva questa skill",
                "impara questa procedura", "impara questo flusso", "nuova skill"
            ).any { lower.contains(it) }
            if (skillTrigger) {
                val slug = lower
                    .replace(Regex("(crea una skill|salva come skill|salva questa skill|impara questa procedura|impara questo flusso|nuova skill)"), "")
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .take(28)
                    .ifBlank { "skill_${System.currentTimeMillis() % 10000}" }
                val title = userPrompt.take(56).trim()
                repo.upsertSkill(
                    slug = slug,
                    title = title,
                    description = "Skill appresa dalla chat: ${userPrompt.take(90)}",
                    instructionsMd = assistantReply.take(1500)
                )
                val trace = ToolCallTrace(
                    toolName = "save_skill",
                    category = ToolGroup.MEMORY.id,
                    argumentsSummary = "slug=$slug",
                    resultSummary = "Skill '/$slug' salvata automaticamente nella libreria locale.",
                    durationMs = 5,
                    status = "success"
                )
                synchronized(traces) { traces.add(trace) }
                repo.logToolExecution(sessionId, "save_skill", ToolGroup.MEMORY.id, trace.argumentsSummary, trace.resultSummary, 5, true)
            }
        }
    }
}
