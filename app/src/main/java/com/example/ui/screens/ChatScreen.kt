package com.example.ui.screens

import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.data.prefs.ToolGroup
import com.example.agent.ContextAndMemoryEngine
import com.example.agent.PhoneStep
import com.example.agent.phoneStep
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.agent.EngineStatus
import com.example.agent.tools.ToolCallTrace
import com.example.agent.tools.decodeToolTracesFromJson
import com.example.data.db.ChatMessageEntity
import com.example.ui.components.AgentActivityPanel
import com.example.ui.components.RichMarkdownContent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private data class QuickStarter(
    val title: String,
    val prompt: String,
    val icon: ImageVector
)

private val QUICK_STARTERS = listOf(
    QuickStarter(
        title = "Cerca novità sul web",
        prompt = "Cerca sul web le ultime novità su LiteRT-LM e Gemma 4",
        icon = Icons.Default.TravelExplore
    ),
    QuickStarter(
        title = "Stato batteria e sistema",
        prompt = "Controlla la telemetria del mio Pixel e dammi un briefing breve",
        icon = Icons.Default.Smartphone
    ),
    QuickStarter(
        title = "Crea un timer in HTML/JS",
        prompt = "Usa /coding_workflow per creare un timer Pomodoro in HTML/JS",
        icon = Icons.Default.Code
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToModelHub: () -> Unit,
    onNavigateToStudio: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val messages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val incomingSharedPrompt by viewModel.incomingSharedPrompt.collectAsStateWithLifecycle()

    val accessibilityConnected by MobHarnAccessibilityService.isConnected.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var inputText by rememberSaveable { mutableStateOf("") }
    var showSkillSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var lastPhoneCheck by rememberSaveable { mutableStateOf<String?>(null) }
    var showAgentReport by remember { mutableStateOf(false) }
    var showTopOverflow by remember { mutableStateOf(false) }
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }

    val showScrollToBottom by remember {
        derivedStateOf { listState.canScrollForward }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { onShowMessage(it) }
    }

    LaunchedEffect(incomingSharedPrompt) {
        incomingSharedPrompt?.let { shared ->
            inputText = shared
            viewModel.consumeSharedPrompt()
        }
    }

    val activeModel = (engineStatus as? EngineStatus.Ready)?.model
    val activeModelName = activeModel?.displayName ?: "Gemma 4"
    val thinkingEnabled = activeModel?.thinkingEnabled ?: true

    val streamingScrollBucket = (uiState.streamingVisibleText.length + uiState.streamingThinkingText.length) / 80
    LaunchedEffect(messages.size, uiState.isGenerating, streamingScrollBucket, uiState.liveToolTraces.size) {
        if (messages.isNotEmpty() || uiState.isGenerating) {
            val totalItems = messages.size + if (uiState.isGenerating) 1 else 0
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= totalItems - 3 || !uiState.isGenerating) {
                listState.scrollToItem((totalItems - 1).coerceAtLeast(0))
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.attachImageFromUri(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = pendingCameraPath
        if (success && path != null) {
            viewModel.attachCapturedImageFile(path)
        }
    }

    fun launchCameraCapture() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        pendingCameraPath = file.absolutePath
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCameraCapture()
        else onShowMessage("Permesso fotocamera negato.")
    }

    // Tool Approval Gate Dialog
    pendingApproval?.let { approval ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveToolApproval(false) },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Conferma azione") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(approval.description, style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(approval.toolName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(approval.argumentsSummary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.resolveToolApproval(true)
                    },
                    modifier = Modifier.testTag("approve_tool_button")
                ) { Text("Consenti") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.resolveToolApproval(false) },
                    modifier = Modifier.testTag("deny_tool_button")
                ) { Text("Rifiuta") }
            }
        )
    }

    if (showAgentReport) {
        val step = phoneStep(ToolGroup.PHONE_USE.id in prefs.enabledToolGroups, accessibilityConnected)
        val inference by viewModel.lastInference.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showAgentReport = false },
            title = { Text("Verifica telefono") },
            text = { Text("${step.status}\n${lastPhoneCheck.orEmpty()}\n\nBackend: ${(engineStatus as? EngineStatus.Ready)?.activeBackend ?: "non pronto"}\n${inference ?: "Misure LiteRT non disponibili."}") },
            confirmButton = { Button(onClick = {
                showAgentReport = false
                val current = phoneStep(ToolGroup.PHONE_USE.id in viewModel.preferences.value.enabledToolGroups, MobHarnAccessibilityService.isConnected.value)
                if (current != step) onShowMessage("Stato cambiato: ${current.status}")
                else when (step) {
                    PhoneStep.STUDIO -> onNavigateToStudio()
                    PhoneStep.SETTINGS -> MobHarnAccessibilityService.openAccessibilitySettings(context)
                    PhoneStep.RECENTS -> scope.launch {
                        val result = MobHarnAccessibilityService.instance?.openVerifiedRecents {
                            ToolGroup.PHONE_USE.id in viewModel.preferences.value.enabledToolGroups
                        } ?: "Servizio disconnesso."
                        lastPhoneCheck = "Ultima verifica: $result"
                        onShowMessage(result)
                    }
                }
            }) { Text(step.label) } },
            dismissButton = { OutlinedButton(onClick = { showAgentReport = false }) { Text("Chiudi") } }
        )
    }

    if (showSkillSheet) {
        ModalBottomSheet(onDismissRequest = { showSkillSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Scegli una Skill", style = MaterialTheme.typography.titleLarge)
                val enabledSkills = remember(skills, inputText) {
                    ContextAndMemoryEngine.rankSkillsForPrompt(inputText, skills, limit = skills.size)
                }
                if (enabledSkills.isEmpty()) {
                    Text(
                        "Nessuna skill attiva. Puoi crearne una nella scheda Memoria.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    enabledSkills.forEach { skill ->
                        Surface(
                            onClick = {
                                inputText = "Usa la skill /${skill.slug}: $inputText"
                                showSkillSheet = false
                            },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("/${skill.slug} • ${skill.title}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.widthIn(max = 308.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Chat", style = MaterialTheme.typography.titleLarge)
                        FilledTonalIconButton(
                            onClick = {
                                viewModel.createNewSession()
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.testTag("drawer_new_session_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_session))
                        }
                    }

                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::searchHistory,
                        placeholder = { Text("Cerca nelle chat...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (uiState.searchQuery.isNotBlank()) {
                            {
                                IconButton(onClick = { viewModel.searchHistory("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Pulisci")
                                }
                            }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fts_search_input")
                    )

                    if (uiState.searchQuery.isNotBlank()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.searchResults, key = { it.id }) { hit ->
                                Surface(
                                    onClick = {
                                        viewModel.selectSession(hit.sessionId)
                                        viewModel.searchHistory("")
                                        scope.launch { drawerState.close() }
                                    },
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            hit.content,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(sessions, key = { it.id }) { session ->
                                val selected = session.id == uiState.activeSessionId
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectSession(session.id)
                                            scope.launch { drawerState.close() }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = session.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Elimina sessione",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
        ) {
            // Minimal, calm TopAppBar
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.testTag("open_sessions_drawer_button")
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_open_drawer))
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("MobHarn", style = MaterialTheme.typography.titleLarge)
                        EngineStatusPill(status = engineStatus, onClick = onNavigateToModelHub)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.createNewSession() },
                        modifier = Modifier.testTag("new_chat_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_session))
                    }
                    Box {
                        IconButton(onClick = { showTopOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Altre opzioni")
                        }
                        DropdownMenu(
                            expanded = showTopOverflow,
                            onDismissRequest = { showTopOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (thinkingEnabled) "Ragionamento (CoT): Attivo"
                                        else "Ragionamento (CoT): Spento"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (thinkingEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                                        contentDescription = null,
                                        tint = if (thinkingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    showTopOverflow = false
                                    viewModel.toggleThinkingForActiveModel()
                                },
                                modifier = Modifier.testTag("toggle_cot_menu_item")
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        val usedK = formatTokensShort(uiState.contextTokensUsed)
                                        val maxK = formatTokensShort(uiState.contextTokensMax)
                                        Text(
                                            if (uiState.contextTokensUsed > 0) "Compatta contesto ($usedK / $maxK)"
                                            else "Compatta contesto chat"
                                        )
                                        if (uiState.hasSessionDigest) {
                                            Text(
                                                "Checkpoint attivo",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Compress, contentDescription = null) },
                                onClick = {
                                    showTopOverflow = false
                                    viewModel.compactCurrentSession()
                                },
                                modifier = Modifier.testTag("compact_context_button")
                            )
                            DropdownMenuItem(text = { Text("Verifica telefono") }, onClick = {
                                showTopOverflow = false
                                showAgentReport = true
                            })
                            if (!accessibilityConnected && ToolGroup.PHONE_USE.id in prefs.enabledToolGroups) {
                                DropdownMenuItem(text = { Text("Apri Accessibilità") }, onClick = {
                                    showTopOverflow = false
                                    MobHarnAccessibilityService.openAccessibilitySettings(context)
                                })
                            }
                            DropdownMenuItem(
                                text = { Text("Apri Studio") },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                onClick = {
                                    showTopOverflow = false
                                    onNavigateToStudio()
                                }
                            )
                        }
                    }
                }
            )

            // Ultra-subtle 2.dp hairline context usage indicator (visible only during active threads)
            if (messages.isNotEmpty() && uiState.contextTokensUsed > 0 && uiState.contextTokensMax > 0) {
                val ctxFraction = (uiState.contextTokensUsed.toFloat() / uiState.contextTokensMax.toFloat())
                    .coerceIn(0.02f, 1f)
                LinearProgressIndicator(
                    progress = { ctxFraction },
                    color = if (ctxFraction >= 0.65f) StatusWarning else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            // Quick Sandbox Output Banner (only when user runs a code snippet from chat)
            AnimatedVisibility(visible = uiState.quickSandboxBanner != null) {
                uiState.quickSandboxBanner?.let { banner ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.dismissQuickSandboxBanner() }) {
                                Icon(Icons.Default.Close, contentDescription = "Chiudi")
                            }
                        }
                    }
                }
            }

            // Message Thread or Calm Minimal Welcome State
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !uiState.isGenerating) {
                    ChatEmptyWelcome(
                        engineStatus = engineStatus,
                        onPickPrompt = { starter ->
                            if (engineStatus is EngineStatus.Ready) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.sendMessage(starter)
                            } else {
                                inputText = starter
                                onShowMessage("Carica prima il modello nella scheda Modello")
                            }
                        },
                        onGoToModelHub = onNavigateToModelHub
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatMessageBubble(
                                message = msg,
                                modelName = activeModelName,
                                onSaveCode = viewModel::saveCodeBlockToWorkspace,
                                onRunCode = viewModel::runCodeBlockInSandbox,
                                onCopyMessage = { content ->
                                    clipboard.setText(AnnotatedString(content))
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShowMessage("Copiato negli appunti")
                                }
                            )
                        }

                        if (uiState.isGenerating) {
                            item(key = "streaming_assistant") {
                                StreamingAssistantBlock(
                                    modelName = activeModelName,
                                    startedAtMs = uiState.generationStartedAtMs,
                                    thinking = uiState.streamingThinkingText,
                                    thinkingDurationSec = uiState.thinkingDurationSec,
                                    visibleText = uiState.streamingVisibleText,
                                    traces = uiState.liveToolTraces,
                                    tps = uiState.liveTokensPerSec,
                                    thermalNote = uiState.liveThermalNote
                                )
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottom && messages.isNotEmpty(),
                        enter = fadeIn() + scaleIn(initialScale = 0.85f),
                        exit = fadeOut() + scaleOut(targetScale = 0.85f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    val lastIdx = (messages.size + if (uiState.isGenerating) 1 else 0) - 1
                                    listState.animateScrollToItem(lastIdx.coerceAtLeast(0))
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Scorri in fondo")
                        }
                    }
                }
            }

            // Clean, Uncluttered Single-Pill Composer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = uiState.attachedImagePath != null) {
                    uiState.attachedImagePath?.let { path ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AsyncImage(
                                    model = remember(path) {
                                        ImageRequest.Builder(context)
                                            .data(File(path))
                                            .size(256)
                                            .crossfade(false)
                                            .build()
                                    },
                                    contentDescription = "Immagine allegata",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Text(
                                    "Immagine allegata",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.clearAttachedImage() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Rimuovi immagine", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                val suggestion = remember(inputText, skills, prefs.enabledToolGroups) {
                    if (ToolGroup.MEMORY.id !in prefs.enabledToolGroups || inputText.isBlank() || '/' in inputText) null
                    else ContextAndMemoryEngine.rankSkillsForPrompt(inputText.take(512), skills, 1, matchesOnly = true).firstOrNull()
                }
                if (suggestion != null && !uiState.isGenerating) {
                    OutlinedButton(onClick = { inputText = "Usa la skill /${suggestion.slug}: $inputText" }) {
                        Text("Usa procedura: ${suggestion.title}", maxLines = 1)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box {
                            IconButton(
                                onClick = { showAttachMenu = true },
                                modifier = Modifier.testTag("composer_attach_menu_button")
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Allega o scegli skill",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Foto da Galleria") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.testTag("attach_gallery_button")
                                )
                                DropdownMenuItem(
                                    text = { Text("Scatta Foto") },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.CAMERA
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) launchCameraCapture()
                                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    },
                                    modifier = Modifier.testTag("capture_camera_button")
                                )
                                DropdownMenuItem(
                                    text = { Text("Usa una Skill") },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        showSkillSheet = true
                                    },
                                    modifier = Modifier.testTag("pick_skill_button")
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "Chiedi a MobHarn...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 5,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_prompt_input")
                            )
                        }

                        if (uiState.isGenerating) {
                            FilledIconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.stopGeneration()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("stop_generation_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.cd_stop_generation))
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val toSend = inputText
                                    inputText = ""
                                    viewModel.sendMessage(toSend)
                                },
                                enabled = inputText.isNotBlank() || uiState.attachedImagePath != null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("send_message_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.cd_send_message),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTokensShort(tokens: Int): String {
    return if (tokens >= 1000) {
        String.format(Locale.US, "%.1fK", tokens / 1000f)
    } else {
        "$tokens"
    }
}

@Composable
private fun EngineStatusPill(
    status: EngineStatus,
    onClick: () -> Unit
) {
    val (dotColor, label) = when (status) {
        is EngineStatus.Ready -> StatusSuccess to status.activeBackend.substringBefore(" ")
        is EngineStatus.Loading -> StatusWarning to "Avvio..."
        is EngineStatus.Error -> StatusError to "Errore"
        EngineStatus.Idle -> MaterialTheme.colorScheme.outline to "Offline"
    }
    Surface(
        color = dotColor.copy(alpha = 0.14f),
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChatEmptyWelcome(
    engineStatus: EngineStatus,
    onPickPrompt: (String) -> Unit,
    onGoToModelHub: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            shape = CircleShape,
            modifier = Modifier.size(58.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "M",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Come posso aiutarti?",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "AI locale sul tuo dispositivo",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (engineStatus !is EngineStatus.Ready) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onGoToModelHub,
                shape = CircleShape,
                modifier = Modifier.testTag("empty_go_model_hub_button")
            ) {
                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Collega modello .litertlm")
            }
        }

        Spacer(Modifier.height(28.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        ) {
            QUICK_STARTERS.forEach { starter ->
                Surface(
                    onClick = { onPickPrompt(starter.prompt) },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            starter.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            starter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    modelName: String,
    onSaveCode: (String, String) -> Unit,
    onRunCode: (String, String) -> Unit,
    onCopyMessage: (String) -> Unit
) {
    val isUser = message.role == "user"
    val traces = remember(message.toolCallsJson) { decodeToolTracesFromJson(message.toolCallsJson) }

    if (isUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!message.imagePath.isNullOrBlank()) {
                        val imgContext = LocalContext.current
                        AsyncImage(
                            model = remember(message.imagePath) {
                                ImageRequest.Builder(imgContext)
                                    .data(File(message.imagePath))
                                    .size(640)
                                    .crossfade(false)
                                    .build()
                            },
                            contentDescription = "Allegato utente",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    } else {
        // Clean, borderless editorial layout for assistant responses with unified activity & CoT box
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AgentActivityPanel(
                modelName = modelName,
                isRunning = false,
                startedAtMs = message.id,
                thinkingText = message.thinking,
                thinkingDurationSec = 0,
                hasVisibleOutput = true,
                traces = traces
            )

            RichMarkdownContent(
                text = message.content,
                textColor = MaterialTheme.colorScheme.onBackground,
                onSaveCodeToWorkspace = onSaveCode,
                onRunCodeInSandbox = onRunCode,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { onCopyMessage(message.content) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copia risposta",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (message.tokensPerSec > 0f) {
                    Text(
                        text = String.format(Locale.US, "≈%.1f tok/s", message.tokensPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantBlock(
    modelName: String,
    startedAtMs: Long,
    thinking: String,
    thinkingDurationSec: Int,
    visibleText: String,
    traces: List<ToolCallTrace>,
    tps: Float,
    thermalNote: String? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
    ) {
        AgentActivityPanel(
            modelName = modelName,
            isRunning = true,
            startedAtMs = startedAtMs,
            thinkingText = thinking,
            thinkingDurationSec = thinkingDurationSec,
            hasVisibleOutput = visibleText.isNotBlank(),
            traces = traces
        )

        if (visibleText.isNotBlank()) {
            Text(
                text = visibleText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (tps > 0f || !thermalNote.isNullOrBlank()) {
            Text(
                text = buildString {
                    if (tps > 0f) append(String.format(Locale.US, "≈%.1f tok/s", tps))
                    if (!thermalNote.isNullOrBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(thermalNote)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (!thermalNote.isNullOrBlank()) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
