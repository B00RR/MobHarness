package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.agent.EngineStatus
import com.example.data.db.ModelProfileEntity
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.ModelHubViewModel
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubScreen(
    viewModel: ModelHubViewModel,
    onShowMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshHardware()
        viewModel.events.collect { onShowMessage(it) }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
            title = { Text("Modello", style = MaterialTheme.typography.titleLarge) },
            actions = {
                IconButton(onClick = { viewModel.refreshHardware() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Aggiorna stato")
                }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Clean Status & Import Card (zero giant banner clutter)
            item {
                val statusColor = when (engineStatus) {
                    is EngineStatus.Ready -> StatusSuccess
                    is EngineStatus.Loading -> StatusWarning
                    is EngineStatus.Error -> StatusError
                    EngineStatus.Idle -> MaterialTheme.colorScheme.outline
                }
                Card(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (val s = engineStatus) {
                                        is EngineStatus.Ready -> s.model.displayName
                                        is EngineStatus.Loading -> "Caricamento ${s.modelName}..."
                                        is EngineStatus.Error -> "Errore caricamento modello"
                                        EngineStatus.Idle -> "Nessun modello attivo"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when (val s = engineStatus) {
                                        is EngineStatus.Ready -> "${s.activeBackend} • pronto in ${s.loadTimeMs}ms"
                                        is EngineStatus.Loading -> s.backendLabel
                                        is EngineStatus.Error -> s.message
                                        EngineStatus.Idle -> "Importa un file .litertlm dal dispositivo"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Compact single-row system vitals
                        uiState.hardware?.let { hw ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                VitalChip(
                                    text = String.format(Locale.US, "RAM %.1f GB", hw.availableRamGb),
                                    modifier = Modifier.weight(1f)
                                )
                                VitalChip(
                                    text = String.format(Locale.US, "%.0f°C %s", hw.batteryTempC, hw.thermalLabel),
                                    modifier = Modifier.weight(1f)
                                )
                                VitalChip(
                                    text = "Batt ${hw.batteryPercent}%",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (uiState.isImporting) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(uiState.importStatus, style = MaterialTheme.typography.labelSmall)
                                LinearProgressIndicator(
                                    progress = { uiState.importProgress },
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                                enabled = !uiState.isImporting,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).testTag("import_litertlm_button")
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Importa .litertlm")
                            }
                            if (engineStatus is EngineStatus.Ready) {
                                OutlinedButton(
                                    onClick = { viewModel.unloadModel() },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.testTag("unload_model_button")
                                ) {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Scarica")
                                }
                            }
                        }
                    }
                }
            }

            // Diagnostic Error Card (only visible when there is an actual error)
            if (engineStatus is EngineStatus.Error) {
                val errState = engineStatus as EngineStatus.Error
                item {
                    val clipboard = LocalClipboardManager.current
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("Diagnostica", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString("${errState.message}\n\n${errState.detail}"))
                                    onShowMessage("Diagnostica copiata")
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copia", tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            Text(errState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (errState.detail.isNotBlank()) {
                                Text(
                                    errState.detail,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Clean Local Models Section (Progressive disclosure for tuning)
            if (models.isNotEmpty()) {
                item {
                    Text(
                        "I tuoi modelli",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                items(models, key = { it.id }) { profile ->
                    ModelProfileCard(
                        profile = profile,
                        engineStatus = engineStatus,
                        onLoad = { viewModel.loadModel(profile) },
                        onUnload = { viewModel.unloadModel() },
                        onUpdate = { viewModel.updateModelProfile(it) },
                        onDelete = { viewModel.deleteModel(profile) }
                    )
                }
            }

            // 3. Single Compact Preferences Card
            item {
                Text(
                    "Batteria & Aspetto",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingSwitchRow(
                            title = "Modalità Eco Batteria",
                            subtitle = "Riduce calore e consumi energetici",
                            checked = prefs.ecoThermalMode,
                            onCheckedChange = viewModel::setEcoThermalMode
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        SettingSwitchRow(
                            title = "Auto-scarico in background",
                            subtitle = "Libera RAM e batteria quando chiudi l'app",
                            checked = prefs.autoUnloadInBackground,
                            onCheckedChange = viewModel::setAutoUnloadInBackground
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        SettingSwitchRow(
                            title = "Colori dinamici Pixel",
                            subtitle = "Adatta il tema allo sfondo",
                            checked = prefs.useDynamicColor,
                            onCheckedChange = viewModel::setUseDynamicColor
                        )
                        val modes = listOf(ThemeMode.SYSTEM to "Sistema", ThemeMode.DARK to "Scuro", ThemeMode.LIGHT to "Chiaro")
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { idx, (mode, label) ->
                                SegmentedButton(
                                    selected = prefs.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(idx, modes.size)
                                ) { Text(label) }
                            }
                        }
                    }
                }
            }

            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 14.dp)
                ) {
                    Text(
                        text = "MobHarn v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            }
        }
    }
}

@Composable
private fun VitalChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModelProfileCard(
    profile: ModelProfileEntity,
    engineStatus: EngineStatus,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onUpdate: (ModelProfileEntity) -> Unit,
    onDelete: () -> Unit
) {
    val isLoaded = engineStatus is EngineStatus.Ready && engineStatus.model.id == profile.id
    val isLoading = engineStatus is EngineStatus.Loading && engineStatus.modelName == profile.displayName
    val sizeGb = profile.fileSizeBytes / (1024.0 * 1024.0 * 1024.0)
    var showAdvanced by rememberSaveable(profile.id) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            width = if (isLoaded) 1.5.dp else 1.dp,
            color = if (isLoaded) StatusSuccess.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            profile.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isLoaded) {
                            Surface(color = StatusSuccess.copy(alpha = 0.16f), shape = CircleShape) {
                                Text(
                                    "ATTIVO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusSuccess,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        String.format(Locale.US, "%.2f GB • %s • %dK ctx", sizeGb, profile.preferredBackend, profile.maxNumTokens / 1024),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Compact toggle to reveal advanced options only when wanted
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier.clip(CircleShape).clickable { showAdvanced = !showAdvanced }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Opzioni modello",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Primary single CTA
            Button(
                onClick = if (isLoaded) onUnload else onLoad,
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("load_model_${profile.id}")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Caricamento...")
                } else if (isLoaded) {
                    Text("Scarica dalla RAM")
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Carica (${profile.preferredBackend})")
                }
            }

            // Hidden by default: Advanced tuning panel
            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    Text(
                        File(profile.filePath).name,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val fileLower = File(profile.filePath).name.lowercase()
                    val isAotNpuFile = fileLower.contains("tensor") || fileLower.contains("_g5") || fileLower.contains("_g6") || fileLower.contains("npu")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("GPU", "NPU", "CPU")) { backend ->
                            FilterChip(
                                selected = profile.preferredBackend.equals(backend, ignoreCase = true),
                                onClick = { onUpdate(profile.copy(preferredBackend = backend)) },
                                label = { Text(backend) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = profile.supportsVision,
                                onClick = { onUpdate(profile.copy(supportsVision = !profile.supportsVision)) },
                                label = { Text("Visione") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = profile.thinkingEnabled,
                                onClick = { onUpdate(profile.copy(thinkingEnabled = !profile.thinkingEnabled)) },
                                label = { Text("Thinking") }
                            )
                        }
                    }
                    if (profile.preferredBackend.equals("NPU", ignoreCase = true) && !isAotNpuFile) {
                        Text(
                            text = "La NPU richiede Tensor G5+ e un file AOT (*_Google_Tensor_G5.litertlm). Per i file standard usa GPU.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusWarning
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf(4096, 6144, 8192, 16384)) { ctx ->
                            val label = when (ctx) {
                                4096 -> "4K Min RAM"
                                6144 -> "6K Eco"
                                else -> "${ctx / 1024}K"
                            }
                            FilterChip(
                                selected = profile.maxNumTokens == ctx,
                                onClick = { onUpdate(profile.copy(maxNumTokens = ctx)) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Text(
                        text = if (profile.maxNumTokens >= 8192) {
                            "Con Modalità Eco attiva la KV-cache resta ≤ 6K per risparmiare RAM; le chat lunghe usano l'auto-compattamento."
                        } else {
                            "RAM ridotta: la KV-cache resta leggera mentre l'auto-compattamento gestisce conversazioni lunghe."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column {
                        Text(
                            String.format(Locale.US, "Temperatura: %.2f", profile.temperature),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = profile.temperature,
                            onValueChange = { onUpdate(profile.copy(temperature = it)) },
                            valueRange = 0.0f..1.5f
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rimuovi modello", color = StatusError)
                        }
                    }
                }
            }
        }
    }
}
