package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.data.db.CustomToolEntity
import com.example.data.prefs.ToolGroup
import com.example.ui.theme.CodeSurfaceDark
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
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
    val tabs = listOf("Codice", "Strumenti", "Automazioni")

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
        if (granted) onShowMessage("Permesso posizione concesso.")
        else onShowMessage("Permesso posizione negato.")
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onShowMessage("Permesso notifiche concesso.")
        else onShowMessage("Permesso notifiche negato.")
    }

    if (showNewProjectDialog) {
        var projName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("Nuovo progetto") },
            text = {
                OutlinedTextField(
                    value = projName,
                    onValueChange = { projName = it },
                    label = { Text("Nome (es. my_app)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createProject(projName)
                    showNewProjectDialog = false
                }) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { showNewProjectDialog = false }) { Text("Annulla") } }
        )
    }

    if (showNewFileDialog) {
        var fileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Nuovo file") },
            text = {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Nome (es. app.js, query.sql)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createNewFile(fileName)
                    showNewFileDialog = false
                }) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { showNewFileDialog = false }) { Text("Annulla") } }
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text("Studio", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(
                            onClick = { zipExportLauncher.launch("${uiState.activeProject}.zip") },
                            modifier = Modifier.testTag("export_zip_button")
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = "Esporta ZIP")
                        }
                    }
                }
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
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
                    onRunSandbox = viewModel::runActiveCodeInSandbox,
                    onShowMessage = onShowMessage
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
                    onClearApprovals = { viewModel.clearRememberedApprovals(); onShowMessage("Approvazioni salvate revocate.") },
                    onToggleApproval = viewModel::setRequireApproval,
                    onRunTest = viewModel::runDirectToolTest,
                    onClearLogs = viewModel::clearToolLogs,
                    onOpenAccessibilitySettings = { MobHarnAccessibilityService.openAccessibilitySettings(context) },
                    onOpenAppDetailsSettings = { MobHarnAccessibilityService.openAppDetailsSettings(context) },
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
                            onShowMessage("Notifiche già abilitate.")
                        }
                    }
                )
                2 -> AutomationsTab(
                    automations = automations,
                    onToggle = viewModel::toggleAutomation,
                    onRunNow = viewModel::runAutomationNow,
                    onDelete = viewModel::deleteAutomation
                )
            }
        }

        if (selectedTab == 2) {
            ExtendedFloatingActionButton(
                onClick = { showNewAutomationDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nuova") },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("create_automation_cta")
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
    onRunSandbox: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showProjectMenu by remember { mutableStateOf(false) }
    var showEditorMenu by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Single Clean Project + Files Row
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Box {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showProjectMenu = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    uiState.activeProject,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = "Cambia progetto",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showProjectMenu,
                            onDismissRequest = { showProjectMenu = false }
                        ) {
                            uiState.projects.forEach { proj ->
                                DropdownMenuItem(
                                    text = { Text(proj) },
                                    onClick = {
                                        showProjectMenu = false
                                        onSelectProject(proj)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ Nuovo progetto") },
                                onClick = {
                                    showProjectMenu = false
                                    onNewProject()
                                }
                            )
                        }
                    }
                }

                items(uiState.files, key = { it.relativePath }) { file ->
                    FilterChip(
                        selected = file.relativePath == uiState.selectedFile,
                        onClick = { onSelectFile(file.relativePath) },
                        label = { Text(file.relativePath) }
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

        // Clean Code Editor Card with integrated Run & Save
        item {
            val lineCount = uiState.editorContent.lines().size.coerceAtLeast(1)
            val lineNumbers = remember(lineCount) {
                (1..lineCount).joinToString("\n") { it.toString().padStart(3) }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF253438)),
                colors = CardDefaults.cardColors(containerColor = CodeSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF131C1F))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.isDirty) StatusWarning else StatusSuccess)
                            )
                            Text(
                                text = uiState.selectedFile ?: "Nessun file",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF7BE0B8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onRunSandbox,
                                enabled = !uiState.isSandboxRunning,
                                modifier = Modifier.testTag("run_sandbox_button")
                            ) {
                                if (uiState.isSandboxRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = StatusSuccess)
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Esegui", tint = StatusSuccess)
                                }
                            }
                            IconButton(
                                onClick = onSaveFile,
                                modifier = Modifier.testTag("save_code_file_button")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Salva", tint = Color(0xFF7BE0B8))
                            }
                            Box {
                                IconButton(onClick = { showEditorMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Altro", tint = Color(0xFFB3CCC0))
                                }
                                DropdownMenu(
                                    expanded = showEditorMenu,
                                    onDismissRequest = { showEditorMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Ripristina snapshot ($snapshotCount)") },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                        onClick = {
                                            showEditorMenu = false
                                            onRollback()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Elimina file", color = StatusError) },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusError) },
                                        onClick = {
                                            showEditorMenu = false
                                            onDeleteFile()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(270.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = lineNumbers,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = Color(0xFF556A62),
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        BasicTextField(
                            value = uiState.editorContent,
                            onValueChange = onUpdateCode,
                            textStyle = TextStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
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

        // Compact Mode Selector + Output Console / Preview
        item {
            val sandboxModes = listOf(
                SandboxMode.JS to "JS",
                SandboxMode.SQL to "SQL",
                SandboxMode.PREVIEW to "Preview"
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sandboxModes.forEachIndexed { idx, (mode, label) ->
                    SegmentedButton(
                        selected = uiState.sandboxMode == mode,
                        onClick = { onSelectSandboxMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(idx, sandboxModes.size)
                    ) { Text(label) }
                }
            }
        }

        item {
            if (uiState.sandboxMode == SandboxMode.PREVIEW) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CodeSurfaceDark)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                settings.blockNetworkLoads = true
                            }
                        },
                        update = { webView ->
                            if (webView.tag != uiState.previewNonce) {
                                webView.tag = uiState.previewNonce
                                webView.loadDataWithBaseURL(
                                    "https://preview.mobharn.local/",
                                    uiState.previewHtml,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        onRelease = { webView ->
                            runCatching {
                                webView.stopLoading()
                                webView.clearHistory()
                                webView.clearCache(true)
                                webView.destroy()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Surface(
                    color = CodeSurfaceDark,
                    border = BorderStroke(1.dp, Color(0xFF253438)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = if (uiState.sandboxSuccess) StatusSuccess else StatusError,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (uiState.sandboxSuccess) "Output" else "Errore",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.sandboxSuccess) StatusSuccess else StatusError
                                )
                                if (uiState.sandboxDurationMs > 0) {
                                    Text("${uiState.sandboxDurationMs}ms", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA39B))
                                }
                            }
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(uiState.sandboxOutput))
                                    onShowMessage("Output copiato")
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copia", tint = Color(0xFF9EB3AA), modifier = Modifier.size(15.dp))
                            }
                        }
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
    onClearApprovals: () -> Unit,
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
    var expandGroups by rememberSaveable { mutableStateOf(false) }
    var expandCustomTools by rememberSaveable { mutableStateOf(false) }
    var expandTester by rememberSaveable { mutableStateOf(false) }
    var expandLogs by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Card 1: Essentials (Approval + Accessibility + Permissions)
        item {
            val accColor = if (accessibilityConnected) StatusSuccess else StatusWarning
            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Conferma azioni esterne", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Chiedi OK prima di azioni sul telefono", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = requireApproval,
                            onCheckedChange = onToggleApproval,
                            modifier = Modifier.testTag("approval_policy_switch")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    OutlinedButton(onClick = onClearApprovals) { Text("Revoca le approvazioni salvate") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Controllo Schermo", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Surface(color = accColor.copy(alpha = 0.14f), shape = CircleShape) {
                                    Text(
                                        if (accessibilityConnected) "Attivo" else "Spento",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                "Consente tap e lettura UI nelle altre app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = onOpenAccessibilitySettings,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("open_accessibility_settings_button")
                        ) { Text("Apri") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenAppDetailsSettings, shape = RoundedCornerShape(12.dp)) {
                            Text("Info App")
                        }
                        OutlinedButton(onClick = onRequestLocationPermission, shape = RoundedCornerShape(12.dp)) {
                            Text("Posizione")
                        }
                        OutlinedButton(onClick = onRequestNotificationPermission, shape = RoundedCornerShape(12.dp)) {
                            Text("Notifiche")
                        }
                    }
                }
            }
        }

        // Card 2: Collapsible Tool Groups (collapsed by default!)
        item {
            ExpandableSectionCard(
                title = "Gruppi di Strumenti",
                subtitle = "${enabledGroups.size} di ${ToolGroup.entries.size} attivi",
                expanded = expandGroups,
                onToggle = { expandGroups = !expandGroups }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolGroup.entries.forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(group.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    group.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = group.id in enabledGroups,
                                onCheckedChange = { onToggleGroup(group.id, it) }
                            )
                        }
                    }
                }
            }
        }

        // Card 3: Collapsible Custom JS Tools
        item {
            ExpandableSectionCard(
                title = "Custom Tool JS",
                subtitle = if (customTools.isEmpty()) "Nessun tool personalizzato" else "${customTools.size} registrati",
                expanded = expandCustomTools,
                onToggle = { expandCustomTools = !expandCustomTools }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = onCreateCustomToolClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("add_custom_tool_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Crea Tool JS")
                    }
                    customTools.forEach { tool ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tool.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = tool.enabled, onCheckedChange = { onToggleCustomTool(tool.id, it) })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onRunCustomTool(tool, testInput.ifBlank { "test" }) }, enabled = !isTesting) {
                                        Text("Esegui (${tool.usageCount}x)")
                                    }
                                    TextButton(onClick = { onDeleteCustomTool(tool.id) }) {
                                        Text("Elimina", color = StatusError)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Card 4: Collapsible Live Tool Tester
        item {
            ExpandableSectionCard(
                title = "Testa Strumenti",
                subtitle = "Verifica web, sensori, JS e schermo",
                expanded = expandTester,
                onToggle = { expandTester = !expandTester }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        placeholder = { Text("Input opzionale (query, formula, JS)...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tests = listOf(
                            "web" to "Web",
                            "telemetry" to "Sistema",
                            "screen" to "Schermo",
                            "sensors" to "Sensori",
                            "calc" to "Calcolo",
                            "js" to "JS",
                            "sql" to "SQL",
                            "memory" to "Memoria"
                        )
                        items(tests) { (key, label) ->
                            FilledTonalButton(
                                onClick = { onRunTest(key, testInput) },
                                enabled = !isTesting,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("test_tool_$key")
                            ) { Text(label) }
                        }
                    }
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (quickOutput.isNotBlank()) {
                        Surface(
                            color = CodeSurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = quickOutput,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = Color(0xFFE5ECE8),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Card 5: Collapsible Execution Logs
        item {
            ExpandableSectionCard(
                title = "Log Esecuzioni",
                subtitle = "${logs.size} recenti",
                expanded = expandLogs,
                onToggle = { expandLogs = !expandLogs }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logs.isEmpty()) {
                        Text("Nessun log registrato.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = onClearLogs) { Text("Pulisci log") }
                        }
                        logs.take(10).forEach { log ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(log.toolName, style = MaterialTheme.typography.labelMedium, color = if (log.success) StatusSuccess else StatusError)
                                        Text("${log.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(log.resultPreview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSectionCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Espandi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun AutomationsTab(
    automations: List<com.example.data.db.AutomationTaskEntity>,
    onToggle: (com.example.data.db.AutomationTaskEntity, Boolean) -> Unit,
    onRunNow: (com.example.data.db.AutomationTaskEntity) -> Unit,
    onDelete: (com.example.data.db.AutomationTaskEntity) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    if (automations.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Nessuna automazione", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tocca + Nuova per pianificare briefing o controlli periodici a basso consumo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(automations, key = { it.id }) { task ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${task.actionType} • ${if (task.isPeriodic) "Ogni ${task.intervalMinutes}m" else "Tra ${task.intervalMinutes}m"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = task.enabled, onCheckedChange = { onToggle(task, it) })
                        }
                        if (task.lastResult.isNotBlank()) {
                            Text(
                                text = (task.lastRunAt?.let { "${dateFmt.format(Date(it))} — " } ?: "") + task.lastResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRunNow(task) }) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
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
    var prompt by remember { mutableStateOf("Controlla batteria, memoria e riepilogo") }
    var actionType by remember { mutableStateOf("BRIEFING") }
    var targetUrl by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf("60") }
    var isPeriodic by remember { mutableStateOf(true) }
    val types = listOf("BRIEFING", "TELEMETRY_CHECK", "WEB_MONITOR", "MEMORY_DIGEST", "WORKSPACE_AUDIT")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova automazione") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(types) { t ->
                        FilterChip(selected = actionType == t, onClick = { actionType = t }, label = { Text(t, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Nota") }, maxLines = 2, shape = RoundedCornerShape(14.dp))
                if (actionType == "WEB_MONITOR") {
                    OutlinedTextField(value = targetUrl, onValueChange = { targetUrl = it }, label = { Text("URL (https://...)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                }
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                    label = { Text("Minuti (min 15 periodico)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Ricorrente")
                    Switch(checked = isPeriodic, onCheckedChange = { isPeriodic = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, prompt, actionType, targetUrl, intervalText.toLongOrNull() ?: 60L, isPeriodic) }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
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
    var jsCode by remember { mutableStateOf("return { received: INPUT };") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo Custom Tool JS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome (snake_case)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descrizione") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = paramsDoc, onValueChange = { paramsDoc = it }, label = { Text("Input atteso") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(
                    value = jsCode,
                    onValueChange = { jsCode = it },
                    label = { Text("Codice JS (usa INPUT)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, description, paramsDoc, jsCode) }) { Text("Registra") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
