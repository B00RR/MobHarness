package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.tools.ToolCallTrace
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.delay

private data class ToolPresentation(
    val runningLabel: String,
    val doneLabel: String,
    val icon: ImageVector
)

private fun describeToolAction(toolName: String): ToolPresentation = when (toolName.lowercase()) {
    "web_search" -> ToolPresentation("Ricerca sul web", "Ricerca web completata", Icons.Default.TravelExplore)
    "fetch_webpage" -> ToolPresentation("Lettura pagina web", "Pagina web letta", Icons.Default.TravelExplore)
    "launch_app" -> ToolPresentation("Apertura app", "App aperta", Icons.AutoMirrored.Filled.Launch)
    "open_url" -> ToolPresentation("Apertura link", "Link aperto", Icons.AutoMirrored.Filled.Launch)
    "open_maps" -> ToolPresentation("Apertura Mappe", "Mappe aperte", Icons.AutoMirrored.Filled.Launch)
    "open_system_settings" -> ToolPresentation("Apertura Impostazioni", "Impostazioni aperte", Icons.AutoMirrored.Filled.Launch)
    "set_alarm" -> ToolPresentation("Impostazione sveglia", "Sveglia impostata", Icons.Default.Schedule)
    "set_timer" -> ToolPresentation("Avvio timer", "Timer avviato", Icons.Default.Schedule)
    "create_calendar_event" -> ToolPresentation("Creazione evento calendario", "Evento creato", Icons.Default.Schedule)
    "compose_email", "compose_sms", "dial_number", "share_text" ->
        ToolPresentation("Azione comunicazione", "Azione preparata", Icons.AutoMirrored.Filled.Launch)
    "read_file", "read_file_lines" -> ToolPresentation("Lettura file", "File letto", Icons.Default.Description)
    "write_file", "write_workspace_file", "patch_file", "patch_workspace_file" ->
        ToolPresentation("Modifica file", "File modificato", Icons.Default.Code)
    "list_workspace_files", "grep_workspace", "search_code" ->
        ToolPresentation("Esplorazione workspace", "Workspace esplorato", Icons.Default.FolderOpen)
    "run_javascript", "run_js_sandbox", "run_sql", "run_sql_sandbox", "validate_json_or_regex", "validate_json_regex" ->
        ToolPresentation("Esecuzione in sandbox", "Codice eseguito", Icons.Default.Terminal)
    "calculate_expression" -> ToolPresentation("Calcolo espressione", "Calcolo completato", Icons.Default.Calculate)
    "get_device_telemetry", "get_sensors", "get_sensor_snapshot", "get_approx_location", "read_clipboard" ->
        ToolPresentation("Lettura telemetria dispositivo", "Telemetria letta", Icons.Default.Smartphone)
    "read_screen", "read_screen_ui" -> ToolPresentation("Lettura schermo", "Schermo letto", Icons.Default.TouchApp)
    "tap_element", "tap_screen_text", "tap_coordinates", "tap_screen_coords",
    "type_text", "type_screen_text", "scroll_screen", "system_navigate", "phone_global_action" ->
        ToolPresentation("Controllo schermo", "Azione inviata", Icons.Default.TouchApp)
    "save_memory", "remember_fact" -> ToolPresentation("Salvataggio in memoria", "Ricordo salvato", Icons.Default.Psychology)
    "delete_memory" -> ToolPresentation("Aggiornamento memoria", "Ricordo rimosso", Icons.Default.Psychology)
    "search_memory", "read_vault", "search_past_sessions" ->
        ToolPresentation("Ricerca nella memoria", "Memoria consultata", Icons.Default.Psychology)
    "list_skills" -> ToolPresentation("Lettura libreria skill", "Skill elencate", Icons.Default.AutoAwesome)
    "load_skill" -> ToolPresentation("Caricamento skill", "Skill caricata", Icons.Default.AutoAwesome)
    "save_skill" -> ToolPresentation("Salvataggio nuova skill", "Skill salvata", Icons.Default.AutoAwesome)
    "create_custom_tool", "list_custom_tools", "run_custom_tool", "delete_custom_tool", "update_soul_md", "tune_harness_settings" ->
        ToolPresentation("Auto-evoluzione harness", "Harness aggiornato", Icons.Default.Build)
    "schedule_automation" -> ToolPresentation("Pianificazione automazione", "Automazione pianificata", Icons.Default.Schedule)
    else -> ToolPresentation("Esecuzione $toolName", "Completato $toolName", Icons.Default.Build)
}

internal fun traceLabel(trace: ToolCallTrace): String = when (trace.status) {
    "running" -> "${describeToolAction(trace.toolName).runningLabel}..."
    "error" -> "Azione non riuscita"
    "denied" -> "Azione non autorizzata"
    "success" -> describeToolAction(trace.toolName).doneLabel
    else -> "Esito sconosciuto"
}

private fun formatDurationSeconds(seconds: Int): String {
    if (seconds < 60) return "${seconds}s"
    val mins = seconds / 60
    val rem = seconds % 60
    return "${mins}m ${rem}s"
}

/**
 * Clean, unified Agent Activity & Chain-of-Thought panel inspired by live AI agent step timelines.
 * Shows live elapsed timer, current action headline, expandable step timeline, and readable CoT.
 */
@Composable
fun AgentActivityPanel(
    modelName: String,
    isRunning: Boolean,
    startedAtMs: Long,
    thinkingText: String,
    thinkingDurationSec: Int,
    hasVisibleOutput: Boolean,
    traces: List<ToolCallTrace>,
    modifier: Modifier = Modifier
) {
    // Only render on completed messages if there is actual thinking or tool activity
    if (!isRunning && thinkingText.isBlank() && traces.isEmpty()) return

    var detailsExpanded by rememberSaveable(startedAtMs, isRunning) { mutableStateOf(isRunning) }
    var thoughtExpanded by rememberSaveable(startedAtMs, isRunning) { mutableStateOf(isRunning) }

    // Live 1-second elapsed timer ONLY while isRunning is true (zero battery cost once finished)
    var elapsedSec by remember(startedAtMs) { mutableIntStateOf(0) }
    LaunchedEffect(isRunning, startedAtMs) {
        if (isRunning && startedAtMs > 0L) {
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt().coerceAtLeast(0)
                delay(1000L)
            }
        }
    }

    val activeRunningTool = traces.lastOrNull { it.status == "running" }
    val isActivelyThinking = isRunning && activeRunningTool == null && thinkingText.isNotBlank() && !hasVisibleOutput

    val headline = when {
        activeRunningTool != null -> "${describeToolAction(activeRunningTool.toolName).runningLabel}..."
        isActivelyThinking -> "Ragionamento in corso..."
        isRunning && hasVisibleOutput -> "Scrittura risposta..."
        isRunning -> "Elaborazione richiesta..."
        thinkingText.isNotBlank() && traces.isNotEmpty() -> {
            val countLabel = if (traces.size == 1) "1 azione" else "${traces.size} azioni"
            "Ragionamento • $countLabel"
        }
        thinkingText.isNotBlank() -> {
            if (thinkingDurationSec > 0) "Ragionamento (${formatDurationSeconds(thinkingDurationSec)})"
            else "Catena di pensiero"
        }
        else -> {
            if (traces.size == 1) traceLabel(traces.first())
            else "${traces.size} richieste"
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Subtle top status line like "Gemma 4 • In esecuzione da 12s"
        if (isRunning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = modelName.ifBlank { "MobHarn" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "In esecuzione da ${formatDurationSeconds(elapsedSec)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Single Clean Activity Card
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header Row: status icon + headline + "Mostra dettagli v"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_activity_header")
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(22.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 1.8.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = if (isRunning || thinkingText.isNotBlank()) Icons.Outlined.Lightbulb else Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (detailsExpanded) "Nascondi" else "Dettagli",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (detailsExpanded) "Nascondi dettagli" else "Mostra dettagli",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Expandable Step Timeline + Readable Chain of Thought
                AnimatedVisibility(
                    visible = detailsExpanded,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Step 1: Chain of Thought (Thinking)
                            if (thinkingText.isNotBlank() || isActivelyThinking) {
                                val thoughtLabel = when {
                                    isActivelyThinking -> "Ragionamento in corso..."
                                    thinkingDurationSec > 0 -> "Ha ragionato per ${formatDurationSeconds(thinkingDurationSec)}"
                                    else -> "Catena di pensiero (Chain of Thought)"
                                }
                                // Auto-expanded during live generation, always manually toggleable
                                val showThoughtBody = thoughtExpanded

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .testTag("agent_cot_toggle")
                                            .clickable { thoughtExpanded = !thoughtExpanded }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                if (isActivelyThinking) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 1.6.dp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.Filled.Lightbulb,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = thoughtLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (thinkingText.isNotBlank()) {
                                            Icon(
                                                imageVector = if (showThoughtBody) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = "Leggi chain of thought",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    AnimatedVisibility(visible = showThoughtBody && thinkingText.isNotBlank()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(IntrinsicSize.Min)
                                                .padding(start = 8.dp, bottom = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .fillMaxHeight()
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                            )
                                            Text(
                                                text = thinkingText,
                                                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Step 2..N: Tool execution timeline
                            traces.forEach { trace ->
                                ActivityToolStepRow(trace = trace)
                            }

                            // Subtle placeholder if running and neither thought nor tool has emitted yet
                            if (isRunning && thinkingText.isBlank() && traces.isEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.6.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Analisi del prompt e preparazione contesto...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun ActivityToolStepRow(trace: ToolCallTrace) {
    var expanded by remember(trace.id) { mutableStateOf(false) }
    val info = describeToolAction(trace.toolName)
    val isStepRunning = trace.status == "running"
    val isError = trace.status == "error"
    val isDenied = trace.status == "denied"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(18.dp)
                ) {
                    when {
                        isStepRunning -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 1.6.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        isError -> Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Errore",
                            tint = StatusError,
                            modifier = Modifier.size(16.dp)
                        )
                        else -> Icon(
                            info.icon,
                            contentDescription = null,
                            tint = if (isDenied) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = traceLabel(trace),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) StatusError else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (trace.argumentsSummary.isNotBlank()) {
                        Text(
                            text = trace.argumentsSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (trace.durationMs > 0) {
                    Text(
                        text = "${trace.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
                if (trace.resultSummary.isNotBlank()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Output",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded && trace.resultSummary.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp, bottom = 4.dp)
            ) {
                Text(
                    text = trace.resultSummary,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
