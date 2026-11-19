package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.EngineStatus
import com.example.agent.ThermalAndMemoryGuard
import com.example.agent.tools.PendingToolApproval
import com.example.agent.tools.ToolCallTrace
import com.example.agent.tools.encodeToolTracesToJson
import com.example.data.db.AgentSkillEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.prefs.AppPreferences
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
    val thinkingDurationSec: Int = 0
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
                    _uiState.update { it.copy(activeSessionId = list.first().id) }
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
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                streamingVisibleText = "",
                streamingThinkingText = "",
                liveToolTraces = emptyList(),
                liveThermalNote = null,
                generationStartedAtMs = 0L,
                thinkingDurationSec = 0
            )
        }
        viewModelScope.launch {
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
                    thinkingDurationSec = 0
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

    fun resolveToolApproval(approved: Boolean) {
        container.approvalCoordinator.resolve(approved)
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
            runtime.resetConversation()
            val msgs = repo.getMessages(sessionId)
            if (msgs.size >= 4) {
                val digest = msgs.takeLast(4).joinToString(" | ") { "${it.role}: ${it.content.take(90)}" }
                repo.upsertMemoryAndSync(
                    category = "FACT",
                    keyName = "riepilogo_sessione_$sessionId",
                    content = digest,
                    importance = 3
                )
            }
            _events.tryEmit("Contesto KV liberato e sincronizzato con MEMORY.md.")
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
            val uiThrottleIntervalMs = 95L // ~10.5 UI refreshes/sec cuts UI CPU/GPU heat by ~80%

            try {
                val routedGroups = container.toolkitRegistry.selectRoutedToolGroups(
                    userPrompt = prompt,
                    enabledGroups = currentPrefs.enabledToolGroups
                )
                val priorMessages = repo.getMessages(sessionId)
                    .dropLast(1)
                    .takeLast(4)
                val recentHistoryPairs = priorMessages.map { it.role to it.content }

                val systemPrompt = container.promptAssembler.assemble(
                    activeProject = currentPrefs.activeProjectName,
                    routedGroups = routedGroups
                )
                runtime.streamTurn(
                    sessionId = sessionId,
                    systemInstruction = systemPrompt,
                    userPrompt = prompt,
                    imageFilePath = imagePath,
                    enabledToolGroups = routedGroups,
                    recentHistory = recentHistoryPairs,
                    ecoThermalMode = currentPrefs.ecoThermalMode
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

                    if (lastUiPushMs == 0L || now - lastUiPushMs >= uiThrottleIntervalMs) {
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
                                thinkingDurationSec = currentThinkSec
                            )
                        }
                    }
                }

                val finalText = visibleBuilder.toString().trim()
                    .ifBlank { if (traces.isNotEmpty()) "Strumenti eseguiti con successo." else "(risposta vuota)" }
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
            } catch (t: Throwable) {
                val partial = visibleBuilder.toString().trim()
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
}
