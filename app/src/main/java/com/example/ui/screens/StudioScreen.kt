package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agent.phoneuse.HermesAccessibilityService
import com.example.data.db.CustomToolEntity
import com.example.data.prefs.ToolGroup
import com.example.ui.theme.CodeSurfaceDark
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.viewmodel.SandboxMode
import com.example.ui.viewmodel.StudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    onShowMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val toolLogs by viewModel.toolLogs.collectAsStateWithLifecycle()
    val automations by viewModel.automations.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val customTools by viewModel.customTools.collectAsStateWithLifecycle()
    val accessibilityConnected by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Codice & Sandbox", "Strumenti", "Automazioni")

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewAutomationDialog by remember { mutableStateOf(false) }
    var showNewCustomToolDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshWorkspace()
        viewModel.events.collect { onShowMessage(it) }
    }

    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportProjectZip(uri)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onShowMessage("Permesso posizione approssimativa concesso.")
        else onShowMessage("Permesso posizione negato.")
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onShowMessage("Permesso notifiche automazioni concesso.")
        else onShowMessage("Permesso notifiche negato.")
    }

    if (showNewProjectDialog) {
        var projName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("Nuovo Progetto Coding") },
            text = {
                OutlinedTextField(
                    value = projName,
                    onValueChange = { projName = it },
                    label = { Text("Nome progetto (es. my_app)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createProject(projName)
                    showNewProjectDialog = false
                }) { Text("Crea") }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Annulla") }
            }
        )
    }

    if (showNewFileDialog) {
        var fileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Nuovo File nel Workspace") },
            text = {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Percorso (es. utils.js, query.sql, Main.kt)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createNewFile(fileName)
                    showNewFileDialog = false
                }) { Text("Crea") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Annulla") }
            }
        )
    }

    if (showNewAutomationDialog) {
        CreateAutomationDialog(
            onDismiss = { showNewAutomationDialog = false },
            onCreate = { name, prompt, actionType, url, interval, periodic ->
                viewModel.createAutomation(name, prompt, actionType, url, interval, periodic)
                showNewAutomationDialog = false
            }
        )
    }

    if (showNewCustomToolDialog) {
        CreateCustomToolDialog(
            onDismiss = { showNewCustomToolDialog = false },
            onCreate = { name, desc, params, code ->
                viewModel.createCustomTool(name, desc, params, code)
                showNewCustomToolDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            title = { Text("Studio & Strumenti", style = MaterialTheme.typography.titleLarge) },
            actions = {
                if (selectedTab == 0) {
                    IconButton(
                        onClick = { zipExportLauncher.launch("${uiState.activeProject}.zip") },
                        modifier = Modifier.testTag("export_zip_button")
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = "Esporta progetto ZIP")
                    }
                } else if (selectedTab == 2) {
                    IconButton(
                        onClick = { showNewAutomationDialog = true },
                        modifier = Modifier.testTag("add_automation_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Nuova automazione")
                    }
                }
            }
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            tabs.forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                    modifier = Modifier.testTag("studio_tab_$idx")
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        when (selectedTab) {
            0 -> CodingWorkspaceTab(
                uiState = uiState,
                snapshotCount = snapshots.size,
                onSelectProject = viewModel::selectProject,
                onNewProject = { showNewProjectDialog = true },
                onSelectFile = viewModel::selectFile,
                onNewFile = { showNewFileDialog = true },
                onUpdateCode = viewModel::updateEditorContent,
                onSaveFile = viewModel::saveCurrentFile,
                onRollback = viewModel::rollbackSelectedFile,
                onDeleteFile = viewModel::deleteSelectedFile,
                onSelectSandboxMode = viewModel::setSandboxMode,
                onRunSandbox = viewModel::runActiveCodeInSandbox
            )
            1 -> ToolsAndTesterTab(
                enabledGroups = prefs.enabledToolGroups,
                requireApproval = prefs.requireApprovalForExternalActions,
                accessibilityConnected = accessibilityConnected,
                customTools = customTools,
                quickOutput = uiState.quickToolOutput,
                isTesting = uiState.isTestingTool,
                logs = toolLogs,
                onToggleGroup = viewModel::toggleToolGroup,
                onToggleApproval = viewModel::setRequireApproval,
                onRunTest = viewModel::runDirectToolTest,
                onClearLogs = viewModel::clearToolLogs,
                onOpenAccessibilitySettings = { HermesAccessibilityService.openAccessibilitySettings(context) },
                onOpenAppDetailsSettings = { HermesAccessibilityService.openAppDetailsSettings(context) },
                onCreateCustomToolClick = { showNewCustomToolDialog = true },
                onRunCustomTool = viewModel::runCustomTool,
                onToggleCustomTool = viewModel::toggleCustomTool,
                onDeleteCustomTool = viewModel::deleteCustomTool,
                onRequestLocationPermission = {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onShowMessage("Notifiche già abilitate su questa versione Android.")
                    }
                }
            )
            2 -> AutomationsTab(
                automations = automations,
                onCreateClick = { showNewAutomationDialog = true },
                onToggle = viewModel::toggleAutomation,
                onRunNow = viewModel::runAutomationNow,
                onDelete = viewModel::deleteAutomation
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CodingWorkspaceTab(
    uiState: com.example.ui.viewmodel.StudioUiState,
    snapshotCount: Int,
    onSelectProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onSelectFile: (String) -> Unit,
    onNewFile: () -> Unit,
    onUpdateCode: (String) -> Unit,
    onSaveFile: () -> Unit,
    onRollback: () -> Unit,
    onDeleteFile: () -> Unit,
    onSelectSandboxMode: (SandboxMode) -> Unit,
    onRunSandbox: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Project Selector Row
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.projects) { project ->
                    FilterChip(
                        selected = project == uiState.activeProject,
                        onClick = { onSelectProject(project) },
                        label = { Text(project) },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onNewProject,
                        label = { Text("+ Progetto") }
                    )
                }
            }
        }

        // Files Selector Row
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.files, key = { it.relativePath }) { file ->
                    FilterChip(
                        selected = file.relativePath == uiState.selectedFile,
                        onClick = { onSelectFile(file.relativePath) },
                        label = { Text(file.relativePath) },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onNewFile,
                        label = { Text("+ File") },
                        modifier = Modifier.testTag("new_workspace_file_button")
                    )
                }
            }
        }

        // Code Editor Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CodeSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF151E21))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = (uiState.selectedFile ?: "Nessun file") + if (uiState.isDirty) " •" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF7BE0B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            IconButton(onClick = onRollback) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = "Ripristina snapshot ($snapshotCount)",
                                    tint = Color(0xFFB3CCC0)
                                )
                            }
                            IconButton(onClick = onDeleteFile) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Elimina file",
                                    tint = StatusError
                                )
                            }
                            IconButton(
                                onClick = onSaveFile,
                                modifier = Modifier.testTag("save_code_file_button")
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = "Salva file",
                                    tint = StatusSuccess
                                )
                            }
                        }
                    }

                    val lineCount = uiState.editorContent.lines().size.coerceAtLeast(1)
                    val lineNumbers = remember(lineCount) {
                        (1..lineCount).joinToString("\n") { it.toString().padStart(3) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = lineNumbers,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF5D736B),
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        BasicTextField(
                            value = uiState.editorContent,
                            onValueChange = onUpdateCode,
                            textStyle = TextStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFFE5ECE8)
                            ),
                            cursorBrush = SolidColor(Color(0xFF7BE0B8)),
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                                .testTag("code_editor_input")
                        )
                    }
                }
            }
        }

        // Sandbox Runner Controls
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                SandboxMode.JS to "JS ES6+",
                                SandboxMode.SQL to "SQLite",
                                SandboxMode.PREVIEW to "Web Preview"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = uiState.sandboxMode == mode,
                                    onClick = { onSelectSandboxMode(mode) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Button(
                            onClick = onRunSandbox,
                            enabled = !uiState.isSandboxRunning,
                            modifier = Modifier.testTag("run_sandbox_button")
                        ) {
                            if (uiState.isSandboxRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Esegui")
                            }
                        }
                    }

                    if (uiState.sandboxMode == SandboxMode.PREVIEW) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .background(CodeSurfaceDark, RoundedCornerShape(16.dp))
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.blockNetworkLoads = true
                                    }
                                },
                                update = { webView ->
                                    webView.loadDataWithBaseURL(
                                        "https://preview.hermes.local/",
                                        uiState.previewHtml,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Surface(
                            color = CodeSurfaceDark,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Terminal,
                                            contentDescription = null,
                                            tint = if (uiState.sandboxSuccess) StatusSuccess else StatusError,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (uiState.sandboxSuccess) "Console Output" else "Errore Sandbox",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (uiState.sandboxSuccess) StatusSuccess else StatusError
                                        )
                                    }
                                    if (uiState.sandboxDurationMs > 0) {
                                        Text(
                                            "${uiState.sandboxDurationMs}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF9EB3AA)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = uiState.sandboxOutput,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = Color(0xFFE5ECE8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsAndTesterTab(
    enabledGroups: Set<String>,
    requireApproval: Boolean,
    accessibilityConnected: Boolean,
    customTools: List<CustomToolEntity>,
    quickOutput: String,
    isTesting: Boolean,
    logs: List<com.example.data.db.ToolExecutionLogEntity>,
    onToggleGroup: (String, Boolean) -> Unit,
    onToggleApproval: (Boolean) -> Unit,
    onRunTest: (String, String) -> Unit,
    onClearLogs: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onCreateCustomToolClick: () -> Unit,
    onRunCustomTool: (CustomToolEntity, String) -> Unit,
    onToggleCustomTool: (Long, Boolean) -> Unit,
    onDeleteCustomTool: (Long) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    var testInput by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Phone-Use Accessibility Control Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (accessibilityConnected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Controllo Schermo & Pixel", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (accessibilityConnected) "ATTIVO" else "NON ATTIVO",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (accessibilityConnected) StatusSuccess else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Text(
                        if (accessibilityConnected) {
                            "Gemma 4 può leggere la UI sullo schermo ed eseguire tap, digitazione e scroll in qualsiasi app."
                        } else {
                            "Attiva Hermes Edge in Accessibilità (su Pixel Android 15/16 apri prima Info App → menu ⋮ → Consenti impostazioni con limitazioni)."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Button(
                                onClick = onOpenAccessibilitySettings,
                                modifier = Modifier.testTag("open_accessibility_settings_button")
                            ) { Text("Apri Accessibilità") }
                        }
                        item {
                            OutlinedButton(onClick = onOpenAppDetailsSettings) {
                                Text("Info App")
                            }
                        }
                        item {
                            FilledTonalButton(
                                onClick = { onRunTest("screen", "") },
                                enabled = !isTesting,
                                modifier = Modifier.testTag("test_screen_reader_button")
                            ) { Text("Leggi Schermo") }
                        }
                    }
                }
            }
        }

        // Approval Gate & Permissions Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Conferma azioni esterne", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Chiedi conferma prima di tap schermo, modifiche SOUL.md, custom tool, sveglie, calendario o cron.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = requireApproval,
                            onCheckedChange = onToggleApproval,
                            modifier = Modifier.testTag("approval_policy_switch")
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequestLocationPermission) {
                            Text("Permesso Posizione")
                        }
                        OutlinedButton(onClick = onRequestNotificationPermission) {
                            Text("Permesso Notifiche")
                        }
                    }
                }
            }
        }

        // Self-Evolving Custom JS Tools Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Custom Tools Auto-Creati (${customTools.size})", style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(
                    onClick = onCreateCustomToolClick,
                    modifier = Modifier.testTag("add_custom_tool_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Crea Tool JS")
                }
            }
        }
        items(customTools, key = { it.id }) { tool ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${tool.name} • ${if (tool.createdByAgent) "creato da Gemma 4" else "di sistema"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(tool.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = tool.enabled,
                            onCheckedChange = { onToggleCustomTool(tool.id, it) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { onRunCustomTool(tool, testInput.ifBlank { "Hermes Edge su Pixel 10 Pro XL" }) },
                            enabled = !isTesting
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Esegui (${tool.usageCount}x)")
                        }
                        TextButton(onClick = { onDeleteCustomTool(tool.id) }) {
                            Text("Elimina", color = StatusError)
                        }
                    }
                }
            }
        }

        // Direct Tool Tester (works even without model loaded)
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Testa Strumenti dal Vivo", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        placeholder = { Text("Input opzionale (JSON per custom tool, query web, formula, JS)...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tests = listOf(
                            "screen" to "Schermo UI",
                            "telemetry" to "Telemetria Pixel",
                            "sensors" to "Sensori",
                            "clipboard" to "Appunti",
                            "location" to "Posizione",
                            "calc" to "Calcolatrice",
                            "js" to "JS Sandbox",
                            "sql" to "SQL Sandbox",
                            "web" to "Ricerca Web",
                            "memory" to "Memoria"
                        )
                        items(tests) { (key, label) ->
                            FilledTonalButton(
                                onClick = { onRunTest(key, testInput) },
                                enabled = !isTesting,
                                modifier = Modifier.testTag("test_tool_$key")
                            ) {
                                Text(label)
                            }
                        }
                    }
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (quickOutput.isNotBlank()) {
                        Surface(
                            color = CodeSurfaceDark,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = quickOutput,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = Color(0xFFE5ECE8),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Tool Groups Toggles
        item {
            Text(
                "Gruppi di Strumenti Agentici (${enabledGroups.size}/${ToolGroup.entries.size})",
                style = MaterialTheme.typography.titleSmall
            )
        }
        items(ToolGroup.entries, key = { it.id }) { group ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = group.id in enabledGroups,
                        onCheckedChange = { onToggleGroup(group.id, it) }
                    )
                }
            }
        }

        // Recent Tool Logs
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log Esecuzioni (${logs.size})", style = MaterialTheme.typography.titleSmall)
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClearLogs) { Text("Pulisci") }
                }
            }
        }
        items(logs.take(15), key = { it.id }) { log ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            log.toolName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (log.success) StatusSuccess else StatusError
                        )
                        Text("${log.durationMs}ms", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        log.resultPreview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationsTab(
    automations: List<com.example.data.db.AutomationTaskEntity>,
    onCreateClick: () -> Unit,
    onToggle: (com.example.data.db.AutomationTaskEntity, Boolean) -> Unit,
    onRunNow: (com.example.data.db.AutomationTaskEntity) -> Unit,
    onDelete: (com.example.data.db.AutomationTaskEntity) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automazioni WorkManager", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pianifica briefing, monitoraggi web, audit del codice o digest della memoria in background.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onCreateClick,
                        modifier = Modifier.testTag("create_automation_cta")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Nuova")
                    }
                }
            }
        }

        if (automations.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text("Nessuna automazione pianificata", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Crea un task ricorrente o chiedi a Gemma 4 in chat di schedularne uno.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(automations, key = { it.id }) { task ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${task.actionType} • ${if (task.isPeriodic) "Ogni ${task.intervalMinutes} min" else "Una tantum (+${task.intervalMinutes}m)"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Switch(
                                checked = task.enabled,
                                onCheckedChange = { onToggle(task, it) }
                            )
                        }
                        if (task.lastResult.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    task.lastRunAt?.let { ts ->
                                        Text(
                                            "Ultimo esito (${dateFmt.format(Date(ts))})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(task.lastResult, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onRunNow(task) }) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Esegui ora")
                            }
                            TextButton(onClick = { onDelete(task) }) {
                                Text("Elimina", color = StatusError)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateAutomationDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, Long, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("Briefing Pixel") }
    var prompt by remember { mutableStateOf("Controlla batteria, memoria e riepilogo giornaliero") }
    var actionType by remember { mutableStateOf("BRIEFING") }
    var targetUrl by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf("60") }
    var isPeriodic by remember { mutableStateOf(true) }
    val types = listOf("BRIEFING", "TELEMETRY_CHECK", "WEB_MONITOR", "MEMORY_DIGEST", "WORKSPACE_AUDIT")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova Automazione Background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(types) { t ->
                        FilterChip(
                            selected = actionType == t,
                            onClick = { actionType = t },
                            label = { Text(t, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Istruzione / Nota") },
                    maxLines = 2
                )
                if (actionType == "WEB_MONITOR") {
                    OutlinedTextField(
                        value = targetUrl,
                        onValueChange = { targetUrl = it },
                        label = { Text("URL da monitorare (https://...)") },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                    label = { Text("Intervallo in minuti (min 15 periodico)") },
                    singleLine = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ricorrente (PeriodicWork)")
                    Switch(checked = isPeriodic, onCheckedChange = { isPeriodic = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mins = intervalText.toLongOrNull() ?: 60L
                onCreate(name, prompt, actionType, targetUrl, mins, isPeriodic)
            }) { Text("Pianifica") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}

@Composable
private fun CreateCustomToolDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var paramsDoc by remember { mutableStateOf("inputJson: JSON o stringa") }
    var jsCode by remember { mutableStateOf("return { received: INPUT, length: String(INPUT).length };") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo Custom Tool JS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome (snake_case)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrizione") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = paramsDoc,
                    onValueChange = { paramsDoc = it },
                    label = { Text("Formato INPUT atteso") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = jsCode,
                    onValueChange = { jsCode = it },
                    label = { Text("Codice JS ES6+ (usa INPUT)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, description, paramsDoc, jsCode) }) { Text("Registra") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
