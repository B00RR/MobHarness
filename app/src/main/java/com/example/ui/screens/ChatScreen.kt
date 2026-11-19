package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.agent.EngineStatus
import com.example.agent.tools.ToolCallTrace
import com.example.agent.tools.decodeToolTracesFromJson
import com.example.data.db.ChatMessageEntity
import com.example.ui.components.RichMarkdownContent
import com.example.ui.components.ThinkingBlockCard
import com.example.ui.components.ToolCallTraceCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

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
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var inputText by rememberSaveable { mutableStateOf("") }
    var showSkillSheet by remember { mutableStateOf(false) }
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { onShowMessage(it) }
    }

    LaunchedEffect(uiState.incomingSharedPrompt) {
        uiState.incomingSharedPrompt?.let { shared ->
            inputText = shared
            viewModel.consumeSharedPrompt()
        }
    }

    LaunchedEffect(messages.size, uiState.streamingVisibleText.length) {
        if (messages.isNotEmpty() || uiState.isGenerating) {
            listState.animateScrollToItem((messages.size + if (uiState.isGenerating) 1 else 0).coerceAtLeast(0))
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
            icon = { Icon(Icons.Default.Security, contentDescription = null) },
            title = { Text("Conferma azione agente") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(approval.description, style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = approval.toolName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = approval.argumentsSummary,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resolveToolApproval(true) },
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

    if (showSkillSheet) {
        ModalBottomSheet(onDismissRequest = { showSkillSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Inserisci una Skill Hermes", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Le skill sono procedure riutilizzabili che Gemma 4 carica on-demand senza sprecare token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                skills.filter { it.enabled }.forEach { skill ->
                    Card(
                        onClick = {
                            inputText = "Usa la skill /${skill.slug}: $inputText"
                            showSkillSheet = false
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "/${skill.slug} • ${skill.title}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(skill.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 310.dp)) {
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
                        Text("Sessioni Hermes", style = MaterialTheme.typography.titleLarge)
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
                        placeholder = { Text("Cerca storico FTS...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fts_search_input")
                    )

                    if (uiState.searchQuery.isNotBlank()) {
                        Text(
                            "Risultati FTS (${uiState.searchResults.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.searchResults, key = { it.id }) { hit ->
                                Card(
                                    onClick = {
                                        viewModel.selectSession(hit.sessionId)
                                        viewModel.searchHistory("")
                                        scope.launch { drawerState.close() }
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            "Sessione #${hit.sessionId} • ${hit.role}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
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
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(sessions, key = { it.id }) { session ->
                                val selected = session.id == uiState.activeSessionId
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectSession(session.id)
                                            scope.launch { drawerState.close() }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = session.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Progetto: ${session.activeProject}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Elimina sessione",
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
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                    Column {
                        Text("Hermes Edge", style = MaterialTheme.typography.titleMedium)
                        EngineStatusPill(
                            status = engineStatus,
                            onClick = onNavigateToModelHub
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.compactCurrentSession() },
                        modifier = Modifier.testTag("compact_context_button")
                    ) {
                        Icon(Icons.Default.Compress, contentDescription = "Compatta contesto KV")
                    }
                    IconButton(
                        onClick = { viewModel.createNewSession() },
                        modifier = Modifier.testTag("new_chat_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_session))
                    }
                }
            )

            // Context Status Strip
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vault ~${uiState.estimatedMemoryTokens} tok • ${prefs.enabledToolGroups.size} gruppi tool",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Studio: ${prefs.activeProjectName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigateToStudio() }
                    )
                }
            }

            // Quick Sandbox Execution Banner (when running code directly from chat)
            AnimatedVisibility(visible = uiState.quickSandboxBanner != null) {
                uiState.quickSandboxBanner?.let { banner ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.dismissQuickSandboxBanner() }) {
                                Icon(Icons.Default.Close, contentDescription = "Chiudi output sandbox")
                            }
                        }
                    }
                }
            }

            // Message Thread or Empty State
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !uiState.isGenerating) {
                    ChatEmptyWelcome(
                        engineStatus = engineStatus,
                        onPickPrompt = { starter ->
                            if (engineStatus is EngineStatus.Ready) {
                                viewModel.sendMessage(starter)
                            } else {
                                inputText = starter
                                onShowMessage("Carica prima il tuo Gemma 4 .litertlm nella scheda Modello!")
                            }
                        },
                        onGoToModelHub = onNavigateToModelHub
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatMessageBubble(
                                message = msg,
                                onSaveCode = viewModel::saveCodeBlockToWorkspace,
                                onRunCode = viewModel::runCodeBlockInSandbox
                            )
                        }

                        if (uiState.isGenerating) {
                            item(key = "streaming_assistant") {
                                StreamingAssistantCard(
                                    thinking = uiState.streamingThinkingText,
                                    visibleText = uiState.streamingVisibleText,
                                    traces = uiState.liveToolTraces,
                                    tps = uiState.liveTokensPerSec,
                                    ttftMs = uiState.liveTtftMs,
                                    onSaveCode = viewModel::saveCodeBlockToWorkspace,
                                    onRunCode = viewModel::runCodeBlockInSandbox
                                )
                            }
                        }
                    }
                }
            }

            // Attached image preview
            AnimatedVisibility(visible = uiState.attachedImagePath != null) {
                uiState.attachedImagePath?.let { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Immagine allegata",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Text(
                            "Immagine pronta per visione Gemma 4",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearAttachedImage() }) {
                            Icon(Icons.Default.Close, contentDescription = "Rimuovi immagine")
                        }
                    }
                }
            }

            // Composer Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("attach_gallery_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = stringResource(R.string.cd_attach_image))
                        }
                        IconButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) launchCameraCapture()
                                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.testTag("capture_camera_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.cd_capture_camera))
                        }
                        IconButton(
                            onClick = { showSkillSheet = true },
                            modifier = Modifier.testTag("pick_skill_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.cd_pick_skill))
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Chiedi a Gemma 4 o scrivi codice...") },
                            maxLines = 4,
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_prompt_input")
                        )

                        if (uiState.isGenerating) {
                            FilledIconButton(
                                onClick = { viewModel.stopGeneration() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("stop_generation_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.cd_stop_generation))
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    val toSend = inputText
                                    inputText = ""
                                    viewModel.sendMessage(toSend)
                                },
                                enabled = inputText.isNotBlank() || uiState.attachedImagePath != null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("send_message_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.cd_send_message)
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
private fun EngineStatusPill(
    status: EngineStatus,
    onClick: () -> Unit
) {
    val (dotColor, label) = when (status) {
        is EngineStatus.Ready -> StatusSuccess to "${status.model.displayName.take(18)} • ${status.activeBackend}"
        is EngineStatus.Loading -> StatusWarning to "Caricamento ${status.backendLabel}..."
        is EngineStatus.Error -> StatusError to "Errore motore • Tocca"
        EngineStatus.Idle -> MaterialTheme.colorScheme.outline to "Nessun modello • Importa .litertlm"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChatEmptyWelcome(
    engineStatus: EngineStatus,
    onPickPrompt: (String) -> Unit,
    onGoToModelHub: () -> Unit
) {
    val starters = listOf(
        "Usa /coding_workflow per creare un timer Pomodoro in HTML/JS e testa la logica",
        "Controlla la telemetria del mio Pixel e dammi un briefing",
        "Ricorda in USER.md che sviluppo in Kotlin e lavoro su AI on-device",
        "Cerca sul web le ultime novità su LiteRT-LM e Gemma 4"
    )
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(172.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.img_hermes_hero_1795086568489),
                        contentDescription = stringResource(R.string.cd_hero_banner),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xD90E1412))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            "Hermes Edge su Pixel",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            "Gemma 4 locale • Memoria Markdown • Coding Sandbox • Tool Android",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD6E4DD)
                        )
                    }
                }
            }
        }

        if (engineStatus !is EngineStatus.Ready) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Collega il tuo Gemma 4 (.litertlm)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Importa il file già scaricato sul Pixel e abilita GPU + Visione.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = onGoToModelHub,
                            modifier = Modifier.testTag("empty_go_model_hub_button")
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Configura")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Azioni rapide dell'agente",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(starters) { prompt ->
            Card(
                onClick = { onPickPrompt(prompt) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(prompt, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    onSaveCode: (String, String) -> Unit,
    onRunCode: (String, String) -> Unit
) {
    val isUser = message.role == "user"
    val traces = remember(message.toolCallsJson) { decodeToolTracesFromJson(message.toolCallsJson) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser && message.thinking.isNotBlank()) {
            ThinkingBlockCard(
                thinkingText = message.thinking,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (!isUser && traces.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                traces.forEach { trace -> ToolCallTraceCard(trace = trace) }
            }
        }

        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!message.imagePath.isNullOrBlank()) {
                    AsyncImage(
                        model = File(message.imagePath),
                        contentDescription = "Allegato utente",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                }
                val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
                RichMarkdownContent(
                    text = message.content,
                    textColor = textColor,
                    onSaveCodeToWorkspace = onSaveCode,
                    onRunCodeInSandbox = onRunCode
                )
                if (!isUser && message.tokensPerSec > 0f) {
                    Text(
                        text = String.format(Locale.US, "%.1f tok/s • locale", message.tokensPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantCard(
    thinking: String,
    visibleText: String,
    traces: List<ToolCallTrace>,
    tps: Float,
    ttftMs: Long,
    onSaveCode: (String, String) -> Unit,
    onRunCode: (String, String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (thinking.isNotBlank()) {
            ThinkingBlockCard(thinkingText = thinking, initiallyExpanded = true)
        }
        traces.forEach { trace -> ToolCallTraceCard(trace = trace) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visibleText.isBlank()) {
                    Text(
                        "Gemma 4 sta elaborando on-device...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    RichMarkdownContent(
                        text = visibleText,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        onSaveCodeToWorkspace = onSaveCode,
                        onRunCodeInSandbox = onRunCode
                    )
                }
                if (tps > 0f) {
                    Text(
                        text = String.format(Locale.US, "Streaming • %.1f tok/s • TTFT %dms", tps, ttftMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
