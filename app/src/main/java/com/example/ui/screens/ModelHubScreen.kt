package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.agent.EngineStatus
import com.example.data.db.ModelProfileEntity
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.ModelHubViewModel
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            title = { Text("Modello LiteRT-LM", style = MaterialTheme.typography.titleLarge) },
            actions = {
                IconButton(onClick = { viewModel.refreshHardware() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Aggiorna stato hardware")
                }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Hero Banner & Import CTA
            item {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
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
                                            listOf(Color.Transparent, Color(0xE00E1412))
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                            ) {
                                Text(
                                    "Gemma 4 On-Device Engine",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                                Text(
                                    when (val s = engineStatus) {
                                        is EngineStatus.Ready -> "Attivo: ${s.model.displayName} (${s.activeBackend} • ${s.loadTimeMs}ms)"
                                        is EngineStatus.Loading -> "Caricamento ${s.modelName} su ${s.backendLabel}..."
                                        is EngineStatus.Error -> "Errore: ${s.message}"
                                        EngineStatus.Idle -> "Importa il tuo file .litertlm già scaricato sul Pixel"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD5E6DD)
                                )
                            }
                        }

                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (uiState.isImporting) {
                                Text(uiState.importStatus, style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(
                                    progress = { uiState.importProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                                    enabled = !uiState.isImporting,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("import_litertlm_button")
                                ) {
                                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Importa .litertlm")
                                }
                                if (engineStatus is EngineStatus.Ready) {
                                    OutlinedButton(
                                        onClick = { viewModel.unloadModel() },
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
            }

            // Pixel Hardware Telemetry Card
            uiState.hardware?.let { hw ->
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(hw.modelName, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s • %d core CPU • RAM %.1f / %.1f GB liberi • Storage %.1f GB liberi • Batteria %d%% (%.1f°C)",
                                    hw.androidVersion,
                                    hw.cpuCores,
                                    hw.availableRamGb,
                                    hw.totalRamGb,
                                    hw.freeStorageGb,
                                    hw.batteryPercent,
                                    hw.batteryTempC
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Imported Model Profiles
            item {
                Text("Profili Modello Locali (${models.size})", style = MaterialTheme.typography.titleSmall)
            }

            if (models.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Nessun file .litertlm importato", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Tocca 'Importa .litertlm' per selezionare Gemma 4 E2B o E4B dalla cartella Download del tuo Pixel 10 Pro XL. Tutti i tool, la memoria Markdown e il Coding Studio funzionano già subito.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
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

            // Material You Appearance Preferences
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Stile Material You Pixel", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Colori Dinamici Wallpaper", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Adatta i colori dell'app allo sfondo del tuo Pixel (Android 12+).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = prefs.useDynamicColor,
                                onCheckedChange = viewModel::setUseDynamicColor
                            )
                        }
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
        }
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

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        String.format(Locale.US, "%.2f GB • %s • Context %d", sizeGb, profile.preferredBackend, profile.maxNumTokens),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLoaded) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Rimuovi modello", tint = StatusError)
                }
            }

            // Backend & Vision toggles
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("GPU", "CPU").forEach { backend ->
                    FilterChip(
                        selected = profile.preferredBackend == backend,
                        onClick = { onUpdate(profile.copy(preferredBackend = backend)) },
                        label = { Text(backend) }
                    )
                }
                FilterChip(
                    selected = profile.supportsVision,
                    onClick = { onUpdate(profile.copy(supportsVision = !profile.supportsVision)) },
                    label = { Text("Visione") }
                )
                FilterChip(
                    selected = profile.thinkingEnabled,
                    onClick = { onUpdate(profile.copy(thinkingEnabled = !profile.thinkingEnabled)) },
                    label = { Text("Thinking") }
                )
            }

            // Context Window Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(4096, 8192, 16384, 32768)) { ctx ->
                    FilterChip(
                        selected = profile.maxNumTokens == ctx,
                        onClick = { onUpdate(profile.copy(maxNumTokens = ctx)) },
                        label = { Text("${ctx / 1024}K ctx") }
                    )
                }
            }

            // Temperature Slider
            Column {
                Text(
                    String.format(Locale.US, "Temperatura: %.2f • Top-P: %.2f • Top-K: %d", profile.temperature, profile.topP, profile.topK),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = profile.temperature,
                    onValueChange = { onUpdate(profile.copy(temperature = it)) },
                    valueRange = 0.0f..1.5f
                )
            }

            Button(
                onClick = if (isLoaded) onUnload else onLoad,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().testTag("load_model_${profile.id}")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Inizializzazione LiteRT-LM...")
                } else if (isLoaded) {
                    Text("Modello Attivo — Tocca per scaricare")
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Carica in Memoria (${profile.preferredBackend})")
                }
            }
        }
    }
}
