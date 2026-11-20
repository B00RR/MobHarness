package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.agent.tools.SelfEvolveToolSet
import com.example.data.db.AutomationTaskEntity
import com.example.data.db.CustomToolEntity
import com.example.data.db.ToolExecutionLogEntity
import com.example.data.db.WorkspaceSnapshotEntity
import com.example.data.prefs.AppPreferences
import com.example.data.workspace.WorkspaceFileInfo
import com.example.data.workspace.WorkspaceWritePolicy
import com.example.di.MobHarnAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val loadedFileSha256: String? = null,
    val loadedFileExisted: Boolean = false,
    val canEditSelectedFile: Boolean = true,
    val lastDiffPreview: String? = null,
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
    private val container: MobHarnAppContainer
) : ViewModel() {

    private val repo = container.repository
    fun clearRememberedApprovals() = container.approvalCoordinator.clearRemembered()

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

    val accessibilityConnected: StateFlow<Boolean> = MobHarnAccessibilityService.isConnected

    // Only re-query Room snapshots when activeProject changes — never on editor keystrokes
    val snapshots: StateFlow<List<WorkspaceSnapshotEntity>> = _uiState
        .map { it.activeProject }
        .distinctUntilChanged()
        .flatMapLatest { repo.observeSnapshots(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        val doc = chosen?.let { repo.openWorkspaceFileForEditor(projectName, it).getOrNull() }
        val preview = withContext(Dispatchers.IO) { workspace.buildPreviewHtml(projectName, chosen) }
        _uiState.update {
            it.copy(
                projects = projects,
                activeProject = projectName,
                files = files,
                selectedFile = chosen,
                editorContent = doc?.content.orEmpty(),
                loadedFileSha256 = doc?.sha256,
                loadedFileExisted = doc?.existed ?: false,
                canEditSelectedFile = doc?.editable ?: true,
                isDirty = false,
                previewHtml = preview,
                previewNonce = it.previewNonce + 1
            )
        }
        doc?.notice?.let { _events.tryEmit(it) }
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
            val doc = repo.openWorkspaceFileForEditor(project, relativePath).getOrNull()
            val autoMode = when {
                relativePath.endsWith(".sql", true) -> SandboxMode.SQL
                relativePath.endsWith(".html", true) -> SandboxMode.PREVIEW
                relativePath.endsWith(".js", true) -> SandboxMode.JS
                else -> _uiState.value.sandboxMode
            }
            val preview = withContext(Dispatchers.IO) { workspace.buildPreviewHtml(project, relativePath) }
            _uiState.update {
                it.copy(
                    selectedFile = doc?.normalizedPath ?: relativePath,
                    editorContent = doc?.content.orEmpty(),
                    loadedFileSha256 = doc?.sha256,
                    loadedFileExisted = doc?.existed ?: false,
                    canEditSelectedFile = doc?.editable ?: false,
                    isDirty = false,
                    sandboxMode = autoMode,
                    previewHtml = preview,
                    previewNonce = it.previewNonce + 1
                )
            }
            doc?.notice?.let { _events.tryEmit(it) }
        }
    }

    fun createNewFile(relativePath: String) {
        if (relativePath.isBlank()) return
        viewModelScope.launch {
            val project = _uiState.value.activeProject
            val starter = when {
                relativePath.endsWith(".js", true) -> "// $relativePath\nconsole.log('Ciao da MobHarn!');\n"
                relativePath.endsWith(".html", true) -> "<!DOCTYPE html>\n<html><body style='background:#0e1412;color:#dfe4e0;font-family:sans-serif;padding:20px'><h2>$relativePath</h2></body></html>"
                relativePath.endsWith(".sql", true) -> "-- $relativePath\nSELECT 'MobHarn SQLite pronto' AS status;\n"
                relativePath.endsWith(".kt", true) -> "fun main() {\n    println(\"MobHarn Kotlin\")\n}\n"
                else -> ""
            }
            repo.writeWorkspaceFile(
                projectName = project,
                relativePath = relativePath,
                content = starter,
                summary = "Creato da Studio",
                policy = WorkspaceWritePolicy.RequireAbsent
            ).onSuccess { res ->
                loadProject(project)
                selectFile(res.normalizedPath)
                _uiState.update { it.copy(lastDiffPreview = res.diff) }
                _events.tryEmit("File '${res.normalizedPath}' creato (snapshot registrato).")
            }.onFailure {
                _events.tryEmit("Impossibile creare '$relativePath': ${it.message}")
            }
        }
    }

    fun updateEditorContent(newContent: String) {
        if (!_uiState.value.canEditSelectedFile) return
        _uiState.update { it.copy(editorContent = newContent, isDirty = true) }
    }

    fun dismissDiffPreview() {
        _uiState.update { it.copy(lastDiffPreview = null) }
    }

    fun saveCurrentFile() {
        viewModelScope.launch { saveCurrentFileInternal() }
    }

    private suspend fun saveCurrentFileInternal(): Boolean {
        val state = _uiState.value
        val file = state.selectedFile ?: return false
        val result = repo.writeWorkspaceFile(
            projectName = state.activeProject,
            relativePath = file,
            content = state.editorContent,
            summary = "Modifica manuale editor",
            policy = WorkspaceWritePolicy.MatchExpectedBase(
                expectedBaseSha256 = if (state.loadedFileExisted) state.loadedFileSha256 else null,
                loadedInFull = state.canEditSelectedFile
            )
        )
        return result.fold(
            onSuccess = { res ->
                val preview = withContext(Dispatchers.IO) { workspace.buildPreviewHtml(state.activeProject, res.normalizedPath) }
                val files = workspace.listFiles(state.activeProject)
                _uiState.update {
                    it.copy(
                        files = files,
                        loadedFileSha256 = res.newSha256,
                        loadedFileExisted = res.existsAfter,
                        isDirty = false,
                        lastDiffPreview = if (res.changed) res.diff else it.lastDiffPreview,
                        previewHtml = preview,
                        previewNonce = it.previewNonce + 1
                    )
                }
                _events.tryEmit(
                    if (res.changed) "Salvato '${res.normalizedPath}' (snapshot registrato)."
                    else "Nessuna modifica su '${res.normalizedPath}'."
                )
                true
            },
            onFailure = { err ->
                _events.tryEmit("Errore salvataggio: ${err.message}")
                false
            }
        )
    }

    fun rollbackSelectedFile() {
        val state = _uiState.value
        val file = state.selectedFile ?: return
        viewModelScope.launch {
            repo.undoLatestWorkspaceChange(state.activeProject, file)
                .onSuccess { res ->
                    if (res.existsAfter) {
                        selectFile(res.normalizedPath)
                        _uiState.update { it.copy(lastDiffPreview = res.diff) }
                        _events.tryEmit("Ripristinata versione precedente di '${res.normalizedPath}'.")
                    } else {
                        loadProject(state.activeProject)
                        _uiState.update { it.copy(lastDiffPreview = res.diff) }
                        _events.tryEmit("Rimosso '${res.normalizedPath}' (non esisteva prima della modifica).")
                    }
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Nessuno snapshot disponibile per '$file'.")
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
            if (state.isDirty && !saveCurrentFileInternal()) return@launch
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
                            val svc = MobHarnAccessibilityService.instance
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
            val tag = "mobharn_auto_${System.currentTimeMillis()}"
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
