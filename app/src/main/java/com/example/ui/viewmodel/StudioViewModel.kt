package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.phoneuse.HermesAccessibilityService
import com.example.agent.tools.SelfEvolveToolSet
import com.example.data.db.AutomationTaskEntity
import com.example.data.db.CustomToolEntity
import com.example.data.db.ToolExecutionLogEntity
import com.example.data.db.WorkspaceSnapshotEntity
import com.example.data.prefs.AppPreferences
import com.example.data.workspace.WorkspaceFileInfo
import com.example.di.HermesAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SandboxMode { JS, SQL, PREVIEW }

data class StudioUiState(
    val projects: List<String> = listOf("starter_web"),
    val activeProject: String = "starter_web",
    val files: List<WorkspaceFileInfo> = emptyList(),
    val selectedFile: String? = null,
    val editorContent: String = "",
    val isDirty: Boolean = false,
    val sandboxMode: SandboxMode = SandboxMode.JS,
    val sandboxOutput: String = "Pronto. Premi 'Esegui' per testare il file JS/SQL attivo oppure apri 'Web Preview'.",
    val sandboxSuccess: Boolean = true,
    val sandboxDurationMs: Long = 0L,
    val isSandboxRunning: Boolean = false,
    val previewHtml: String = "",
    val previewNonce: Int = 0,
    val quickToolOutput: String = "",
    val isTestingTool: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModel(
    private val appContext: Context,
    private val container: HermesAppContainer
) : ViewModel() {

    private val repo = container.repository
    private val workspace = repo.workspace
    private val sandbox = container.codeSandbox

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val preferences: StateFlow<AppPreferences> = repo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val toolLogs: StateFlow<List<ToolExecutionLogEntity>> = repo.toolLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val automations: StateFlow<List<AutomationTaskEntity>> = repo.automationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customTools: StateFlow<List<CustomToolEntity>> = repo.customToolsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accessibilityConnected: StateFlow<Boolean> = HermesAccessibilityService.isConnected

    val snapshots: StateFlow<List<WorkspaceSnapshotEntity>> = _uiState
        .flatMapLatest { repo.observeSnapshots(it.activeProject) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshWorkspace()
    }

    fun refreshWorkspace() {
        viewModelScope.launch {
            val projects = workspace.listProjects()
            val prefProject = preferences.value.activeProjectName
            val active = if (prefProject in projects) prefProject else projects.firstOrNull() ?: "starter_web"
            loadProject(active, projects)
        }
    }

    private suspend fun loadProject(projectName: String, knownProjects: List<String>? = null) {
        val projects = knownProjects ?: workspace.listProjects()
        val files = workspace.listFiles(projectName)
        val currentSelected = _uiState.value.selectedFile
        val chosen = if (currentSelected != null && files.any { it.relativePath == currentSelected }) {
            currentSelected
        } else {
            files.firstOrNull { it.extension == "js" }?.relativePath
                ?: files.firstOrNull()?.relativePath
        }
        val content = chosen?.let { workspace.readFile(projectName, it) }.orEmpty()
        val preview = withContext(Dispatchers.IO) { workspace.buildPreviewHtml(projectName, chosen) }
        _uiState.update {
            it.copy(
                projects = projects,
                activeProject = projectName,
                files = files,
                selectedFile = chosen,
                editorContent = content,
                isDirty = false,
                previewHtml = preview,
                previewNonce = it.previewNonce + 1
            )
        }
    }

    fun selectProject(projectName: String) {
        viewModelScope.launch {
            repo.prefs.setActiveProject(projectName)
            loadProject(projectName)
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch {
            val created = workspace.createProject(name)
            repo.prefs.setActiveProject(created)
            loadProject(created)
            _events.tryEmit("Progetto '$created' creato.")
        }
    }

    fun selectFile(relativePath: String) {
        viewModelScope.launch {
            val project = _uiState.value.activeProject
            val content = workspace.readFile(project, relativePath).orEmpty()
            val autoMode = when {
                relativePath.endsWith(".sql", true) -> SandboxMode.SQL
                relativePath.endsWith(".html", true) -> SandboxMode.PREVIEW
                relativePath.endsWith(".js", true) -> SandboxMode.JS
                else -> _uiState.value.sandboxMode
            }
            val preview = withContext(Dispatchers.IO) { workspace.buildPreviewHtml(project, relativePath) }
            _uiState.update {
                it.copy(
                    selectedFile = relativePath,
                    editorContent = content,
                    isDirty = false,
                    sandboxMode = autoMode,
                    previewHtml = preview,
                    previewNonce = it.previewNonce + 1
                )
            }
        }
    }

    fun createNewFile(relativePath: String) {
        if (relativePath.isBlank()) return
        viewModelScope.launch {
            val project = _uiState.value.activeProject
            val starter = when {
                relativePath.endsWith(".js", true) -> "// $relativePath\nconsole.log('Ciao da Hermes Edge!');\n"
                relativePath.endsWith(".html", true) -> "<!DOCTYPE html>\n<html><body style='background:#0e1412;color:#dfe4e0;font-family:sans-serif;padding:20px'><h2>$relativePath</h2></body></html>"
                relativePath.endsWith(".sql", true) -> "-- $relativePath\nSELECT 'Hermes SQLite pronto' AS status;\n"
                relativePath.endsWith(".kt", true) -> "fun main() {\n    println(\"Hermes Edge Kotlin\")\n}\n"
                else -> ""
            }
            workspace.writeFile(project, relativePath, starter).onSuccess {
                loadProject(project)
                selectFile(relativePath)
                _events.tryEmit("File '$relativePath' creato.")
            }.onFailure {
                _events.tryEmit("Percorso non valido: ${it.message}")
            }
        }
    }

    fun updateEditorContent(newContent: String) {
        _uiState.update { it.copy(editorContent = newContent, isDirty = true) }
    }

    fun saveCurrentFile() {
        val state = _uiState.value
        val file = state.selectedFile ?: return
        viewModelScope.launch {
            workspace.writeFile(state.activeProject, file, state.editorContent).onSuccess { prev ->
                repo.recordSnapshot(state.activeProject, file, prev, state.editorContent, "Modifica manuale editor")
                val preview = withContext(Dispatchers.IO) { workspace.buildPreviewHtml(state.activeProject, file) }
                val files = workspace.listFiles(state.activeProject)
                _uiState.update {
                    it.copy(
                        files = files,
                        isDirty = false,
                        previewHtml = preview,
                        previewNonce = it.previewNonce + 1
                    )
                }
                _events.tryEmit("Salvato '$file' (snapshot registrato).")
            }.onFailure {
                _events.tryEmit("Errore salvataggio: ${it.message}")
            }
        }
    }

    fun rollbackSelectedFile() {
        val state = _uiState.value
        val file = state.selectedFile ?: return
        viewModelScope.launch {
            val ok = repo.rollbackLatestSnapshot(state.activeProject, file)
            if (ok) {
                selectFile(file)
                _events.tryEmit("Ripristinata versione precedente di '$file'.")
            } else {
                _events.tryEmit("Nessuno snapshot disponibile per '$file'.")
            }
        }
    }

    fun deleteSelectedFile() {
        val state = _uiState.value
        val file = state.selectedFile ?: return
        viewModelScope.launch {
            if (workspace.deleteFile(state.activeProject, file)) {
                loadProject(state.activeProject)
                _events.tryEmit("File '$file' eliminato.")
            }
        }
    }

    fun setSandboxMode(mode: SandboxMode) {
        val project = _uiState.value.activeProject
        val file = _uiState.value.selectedFile
        viewModelScope.launch {
            val preview = if (mode == SandboxMode.PREVIEW) {
                withContext(Dispatchers.IO) { workspace.buildPreviewHtml(project, file) }
            } else _uiState.value.previewHtml
            _uiState.update {
                it.copy(
                    sandboxMode = mode,
                    previewHtml = preview,
                    previewNonce = it.previewNonce + 1
                )
            }
        }
    }

    fun runActiveCodeInSandbox() {
        val state = _uiState.value
        viewModelScope.launch {
            if (state.isDirty) saveCurrentFile()
            if (state.sandboxMode == SandboxMode.PREVIEW) {
                val html = withContext(Dispatchers.IO) {
                    workspace.buildPreviewHtml(state.activeProject, state.selectedFile)
                }
                _uiState.update { it.copy(previewHtml = html, previewNonce = it.previewNonce + 1) }
                return@launch
            }
            _uiState.update { it.copy(isSandboxRunning = true) }
            val result = when (state.sandboxMode) {
                SandboxMode.SQL -> sandbox.runSql(state.editorContent)
                else -> sandbox.runJavaScript(state.editorContent)
            }
            _uiState.update {
                it.copy(
                    isSandboxRunning = false,
                    sandboxOutput = result.output,
                    sandboxSuccess = result.success,
                    sandboxDurationMs = result.durationMs
                )
            }
        }
    }

    fun exportProjectZip(uri: Uri) {
        val project = _uiState.value.activeProject
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { os ->
                    workspace.exportProjectAsZip(project, os)
                } ?: error("Impossibile aprire il file di destinazione")
            }.onSuccess {
                _events.tryEmit("Progetto '$project' esportato in formato ZIP.")
            }.onFailure {
                _events.tryEmit("Errore esportazione ZIP: ${it.message}")
            }
        }
    }

    // --- Tool Management & Direct Tool Tester ---
    fun toggleToolGroup(groupId: String, enabled: Boolean) {
        viewModelScope.launch { repo.prefs.toggleToolGroup(groupId, enabled) }
    }

    fun setRequireApproval(require: Boolean) {
        viewModelScope.launch { repo.prefs.setRequireApproval(require) }
    }

    fun clearToolLogs() {
        viewModelScope.launch { repo.clearToolLogs() }
    }

    // --- Custom Self-Evolving JS Tools ---
    fun createCustomTool(name: String, description: String, parametersDoc: String, jsCode: String) {
        if (name.isBlank() || jsCode.isBlank()) return
        viewModelScope.launch {
            repo.upsertCustomTool(
                name = name,
                description = description.ifBlank { "Custom JS Tool" },
                parametersDoc = parametersDoc,
                jsCode = jsCode,
                createdByAgent = false
            )
            _events.tryEmit("Custom Tool '$name' registrato nell'harness.")
        }
    }

    fun runCustomTool(tool: CustomToolEntity, inputJson: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingTool = true) }
            val wrapped = SelfEvolveToolSet.buildCustomToolWrapper(tool.jsCode, inputJson)
            val res = sandbox.runJavaScript(wrapped)
            repo.incrementCustomToolUsage(tool.name)
            repo.logToolExecution(
                sessionId = null,
                toolName = "custom:${tool.name}",
                category = "self_evolve",
                argumentsJson = inputJson.ifBlank { "{}" },
                resultPreview = res.output,
                durationMs = res.durationMs,
                success = res.success
            )
            _uiState.update {
                it.copy(
                    isTestingTool = false,
                    quickToolOutput = "[CustomTool ${tool.name} • ${res.durationMs}ms]\n${res.output}"
                )
            }
        }
    }

    fun toggleCustomTool(id: Long, enabled: Boolean) {
        viewModelScope.launch { repo.setCustomToolEnabled(id, enabled) }
    }

    fun deleteCustomTool(id: Long) {
        viewModelScope.launch {
            repo.deleteCustomTool(id)
            _events.tryEmit("Custom Tool eliminato.")
        }
    }

    fun runDirectToolTest(testKey: String, customInput: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingTool = true) }
            val start = System.currentTimeMillis()
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    when (testKey) {
                        "telemetry" -> container.systemActions.getTelemetryReport()
                        "sensors" -> container.systemActions.getSensorSnapshot()
                        "clipboard" -> container.systemActions.readClipboardText()
                        "location" -> container.systemActions.getApproxLocation()
                        "calc" -> sandbox.evaluateMath(customInput.ifBlank { "(2026 - 1995) * sqrt(144) + pow(2, 8)" }).output
                        "web" -> container.systemActions.webSearch(customInput.ifBlank { "Google Gemma 4 LiteRT LM" })
                        "js" -> sandbox.runJavaScript(
                            customInput.ifBlank { "const arr=[1,2,3,4,5]; console.log('Somma:', arr.reduce((a,b)=>a+b,0)); return 'OK';" }
                        ).output
                        "sql" -> sandbox.runSql(
                            customInput.ifBlank { "CREATE TABLE t(k TEXT, v INT); INSERT INTO t VALUES ('pixel', 10), ('gemma', 4); SELECT * FROM t;" }
                        ).output
                        "memory" -> {
                            val list = repo.searchMemories(customInput.ifBlank { "" })
                            if (list.isEmpty()) "Nessun fatto in memoria."
                            else list.joinToString("\n") { "[${it.category}] ${it.keyName}: ${it.content}" }
                        }
                        "screen" -> {
                            val svc = HermesAccessibilityService.instance
                            svc?.captureScreenTree(60)
                                ?: "Servizio Accessibilità non ancora attivo. Tocca 'Apri Accessibilità' qui sopra per abilitarlo."
                        }
                        else -> "Tool test sconosciuto."
                    }
                }.getOrElse { "Errore test tool: ${it.message}" }
            }
            val elapsed = System.currentTimeMillis() - start
            repo.logToolExecution(
                sessionId = null,
                toolName = "test_$testKey",
                category = testKey,
                argumentsJson = customInput.ifBlank { "(default test)" },
                resultPreview = output,
                durationMs = elapsed,
                success = !output.startsWith("Errore")
            )
            _uiState.update {
                it.copy(
                    isTestingTool = false,
                    quickToolOutput = "[$testKey • ${elapsed}ms]\n$output"
                )
            }
        }
    }

    // --- WorkManager Automations ---
    fun createAutomation(
        name: String,
        prompt: String,
        actionType: String,
        targetUrl: String,
        intervalMinutes: Long,
        isPeriodic: Boolean
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val tag = "hermes_auto_${System.currentTimeMillis()}"
            val entity = AutomationTaskEntity(
                name = name.trim(),
                prompt = prompt.trim().ifBlank { "Esecuzione automatica $actionType" },
                actionType = actionType,
                targetUrl = targetUrl.trim(),
                intervalMinutes = intervalMinutes.coerceAtLeast(if (isPeriodic) 15L else 1L),
                isPeriodic = isPeriodic,
                enabled = true,
                workTag = tag
            )
            val id = repo.upsertAutomation(entity)
            container.automationScheduler.schedule(entity.copy(id = id))
            _events.tryEmit("Automazione '$name' pianificata con WorkManager.")
        }
    }

    fun toggleAutomation(task: AutomationTaskEntity, enabled: Boolean) {
        viewModelScope.launch {
            repo.setAutomationEnabled(task.id, enabled)
            val updated = task.copy(enabled = enabled)
            if (enabled) container.automationScheduler.schedule(updated)
            else container.automationScheduler.cancel(updated)
        }
    }

    fun runAutomationNow(task: AutomationTaskEntity) {
        container.automationScheduler.runNow(task)
        _events.tryEmit("Automazione '${task.name}' avviata in background.")
    }

    fun deleteAutomation(task: AutomationTaskEntity) {
        viewModelScope.launch {
            container.automationScheduler.cancel(task)
            repo.deleteAutomation(task.id)
            _events.tryEmit("Automazione '${task.name}' rimossa.")
        }
    }
}
