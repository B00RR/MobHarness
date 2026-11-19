package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.EngineStatus
import com.example.agent.tools.PendingToolApproval
import com.example.agent.tools.ToolCallTrace
import com.example.agent.tools.encodeToolTracesToJson
import com.example.data.db.AgentSkillEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.prefs.AppPreferences
import com.example.di.HermesAppContainer
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ChatUiState(
    val activeSessionId: Long = -1L,
    val isGenerating: Boolean = false,
    val streamingVisibleText: String = "",
    val streamingThinkingText: String = "",
    val liveToolTraces: List<ToolCallTrace> = emptyList(),
    val liveTokensPerSec: Float = 0f,
    val liveTtftMs: Long = 0L,
    val attachedImagePath: String? = null,
    val searchQuery: String = "",
    val searchResults: List<ChatMessageEntity> = emptyList(),
    val quickSandboxBanner: String? = null,
    val estimatedMemoryTokens: Int = 0,
    val incomingSharedPrompt: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val appContext: Context,
    private val container: HermesAppContainer
) : ViewModel() {

    private val repo = container.repository
    private val runtime = container.runtimeManager

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

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

    val activeMessages: StateFlow<List<ChatMessageEntity>> = _uiState
        .flatMapLatest { state ->
            if (state.activeSessionId > 0L) repo.observeMessages(state.activeSessionId)
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
            repo.memoriesFlow.collect {
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
                liveToolTraces = emptyList()
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
            val id = repo.createSession("Sessione Hermes #$nextNum", activeProject)
            _uiState.update {
                it.copy(
                    activeSessionId = id,
                    streamingVisibleText = "",
                    streamingThinkingText = "",
                    liveToolTraces = emptyList(),
                    attachedImagePath = null
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
                runCatching {
                    val dir = File(appContext.cacheDir, "chat_images").apply { mkdirs() }
                    val target = File(dir, "img_${System.currentTimeMillis()}.jpg")
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target.absolutePath
                }.getOrNull()
            }
            if (path != null) {
                _uiState.update { it.copy(attachedImagePath = path) }
            } else {
                _events.tryEmit("Impossibile leggere l'immagine selezionata.")
            }
        }
    }

    fun attachCapturedImageFile(filePath: String) {
        _uiState.update { it.copy(attachedImagePath = filePath) }
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
            sharedImageUri != null -> "Analizza questo screenshot/immagine condivisa dallo schermo del Pixel."
            else -> null
        }
        if (prompt != null) {
            _uiState.update { it.copy(incomingSharedPrompt = prompt) }
            _events.tryEmit("Contenuto condiviso ricevuto in Hermes Edge!")
        }
    }

    fun consumeSharedPrompt() {
        _uiState.update { it.copy(incomingSharedPrompt = null) }
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
            _events.tryEmit("Contesto KV compattato e sincronizzato con MEMORY.md.")
        }
    }

    fun sendMessage(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        val imagePath = _uiState.value.attachedImagePath
        if (prompt.isBlank() && imagePath == null) return
        if (_uiState.value.isGenerating) return

        val ready = engineStatus.value as? EngineStatus.Ready
        if (ready == null) {
            _events.tryEmit("Nessun modello Gemma 4 caricato. Vai nella scheda Modello e importa/carica il tuo .litertlm.")
            return
        }

        generationJob = viewModelScope.launch {
            var sessionId = _uiState.value.activeSessionId
            val currentPrefs = preferences.value
            if (sessionId <= 0L) {
                sessionId = repo.createSession(
                    title = prompt.take(36).ifBlank { "Analisi Immagine" },
                    projectName = currentPrefs.activeProjectName
                )
                _uiState.update { it.copy(activeSessionId = sessionId) }
            }

            // Persist user message immediately
            repo.addMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "user",
                    content = prompt.ifBlank { "Analizza questa immagine." },
                    imagePath = imagePath
                )
            )

            // Update session title if it's still default
            val existingSession = sessions.value.firstOrNull { it.id == sessionId }
            if (existingSession != null && existingSession.title.startsWith("Sessione Hermes")) {
                repo.updateSession(
                    existingSession.copy(
                        title = prompt.take(42).ifBlank { "Sessione Visione" },
                        modelName = ready.model.displayName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Setup live tool monitor
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

            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingVisibleText = "",
                    streamingThinkingText = "",
                    liveToolTraces = emptyList(),
                    liveTokensPerSec = 0f,
                    liveTtftMs = 0L,
                    attachedImagePath = null
                )
            }

            val visibleBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var finalTps = 0f

            try {
                val systemPrompt = container.promptAssembler.assemble(currentPrefs.activeProjectName)
                runtime.streamTurn(
                    sessionId = sessionId,
                    systemInstruction = systemPrompt,
                    userPrompt = prompt,
                    imageFilePath = imagePath,
                    enabledToolGroups = currentPrefs.enabledToolGroups
                ).collect { chunk ->
                    if (chunk.thinkingDelta.isNotEmpty()) thinkingBuilder.append(chunk.thinkingDelta)
                    if (chunk.visibleDelta.isNotEmpty()) visibleBuilder.append(chunk.visibleDelta)
                    finalTps = chunk.tokensPerSec
                    _uiState.update {
                        it.copy(
                            streamingVisibleText = visibleBuilder.toString(),
                            streamingThinkingText = thinkingBuilder.toString(),
                            liveTokensPerSec = chunk.tokensPerSec,
                            liveTtftMs = chunk.ttftMs
                        )
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
                        liveToolTraces = emptyList()
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isGenerating = false) }
    }
}
