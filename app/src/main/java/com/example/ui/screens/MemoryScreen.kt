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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.viewmodel.MemoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    onShowMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedVaultDoc by rememberSaveable { mutableIntStateOf(0) }
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var showAddSkillDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { onShowMessage(it) }
    }

    LaunchedEffect(memories.size) {
        viewModel.reloadVault()
    }

    val exportMdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) viewModel.exportMarkdownBundle(uri)
    }

    if (showAddMemoryDialog) {
        AddMemoryDialog(
            onDismiss = { showAddMemoryDialog = false },
            onSave = { cat, key, content, imp ->
                viewModel.addMemory(cat, key, content, imp)
                showAddMemoryDialog = false
            }
        )
    }

    if (showAddSkillDialog) {
        AddSkillDialog(
            onDismiss = { showAddSkillDialog = false },
            onSave = { slug, title, desc, md ->
                viewModel.saveSkill(slug, title, desc, md)
                showAddSkillDialog = false
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text("Memoria", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(
                        onClick = { exportMdLauncher.launch("mobharn_memory_bundle.md") },
                        modifier = Modifier.testTag("export_markdown_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.cd_export_markdown))
                    }
                }
            )

            val tabs = listOf("Vault", "Fatti", "Skill")
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
                        modifier = Modifier.testTag("memory_tab_$idx")
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    val docNames = listOf("SOUL", "USER", "MEMORY")
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                docNames.forEachIndexed { i, title ->
                                    FilterChip(
                                        selected = selectedVaultDoc == i,
                                        onClick = { selectedVaultDoc = i },
                                        label = { Text(title) }
                                    )
                                }
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    when (selectedVaultDoc) {
                                        0 -> viewModel.saveSoulMd()
                                        1 -> viewModel.saveUserMd()
                                        else -> viewModel.saveMemoryMd()
                                    }
                                },
                                modifier = Modifier.testTag("save_vault_doc_button")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Salva file")
                            }
                        }

                        val currentText = when (selectedVaultDoc) {
                            0 -> uiState.soulMd
                            1 -> uiState.userMd
                            else -> uiState.memoryMd
                        }
                        OutlinedTextField(
                            value = currentText,
                            onValueChange = {
                                when (selectedVaultDoc) {
                                    0 -> viewModel.updateSoulDraft(it)
                                    1 -> viewModel.updateUserDraft(it)
                                    else -> viewModel.updateMemoryDraft(it)
                                }
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 12.dp)
                                .testTag("vault_markdown_editor")
                        )
                    }
                }
                1 -> {
                    val filtered = remember(memories, uiState.searchQuery) {
                        memories.filter { m ->
                            uiState.searchQuery.isBlank() ||
                                m.keyName.contains(uiState.searchQuery, true) ||
                                m.content.contains(uiState.searchQuery, true)
                        }
                    }
                    if (memories.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("Nessun fatto salvato", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Aggiungi informazioni utili su di te o chiedi in chat di ricordarle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (memories.size > 3 || uiState.searchQuery.isNotBlank()) {
                                item {
                                    OutlinedTextField(
                                        value = uiState.searchQuery,
                                        onValueChange = viewModel::setSearchQuery,
                                        placeholder = { Text("Cerca nei fatti...") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        trailingIcon = if (uiState.searchQuery.isNotBlank()) {
                                            {
                                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Pulisci")
                                                }
                                            }
                                        } else null,
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            items(filtered, key = { it.id }) { entry ->
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                entry.keyName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        IconButton(onClick = { viewModel.deleteMemory(entry.id) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    if (skills.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("Nessuna skill", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Crea procedure riutilizzabili richiamabili in chat con /slug.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(skills, key = { it.id }) { skill ->
                                var expanded by rememberSaveable(skill.id) { mutableStateOf(false) }
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expanded = !expanded }
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    "/${skill.slug} • ${skill.title}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    skill.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Switch(
                                                checked = skill.enabled,
                                                onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                                            )
                                        }
                                        AnimatedVisibility(visible = expanded) {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        skill.instructionsMd,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                                        modifier = Modifier.padding(10.dp)
                                                    )
                                                }
                                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                                    TextButton(onClick = { viewModel.deleteSkill(skill.id) }) {
                                                        Text("Elimina", color = StatusError)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedTab == 1 || selectedTab == 2) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (selectedTab == 1) showAddMemoryDialog = true else showAddSkillDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (selectedTab == 1) "Nuovo fatto" else "Nuova skill") },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag(if (selectedTab == 1) "add_memory_button" else "add_skill_button")
            )
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit
) {
    var category by remember { mutableStateOf("FACT") }
    var keyName by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val categories = listOf("USER_PROFILE", "PREFERENCE", "FACT", "PROJECT")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo fatto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.replace("_", " ")) })
                    }
                }
                OutlinedTextField(value = keyName, onValueChange = { keyName = it }, label = { Text("Titolo / chiave") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Cosa ricordare") }, maxLines = 3, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = { Button(onClick = { onSave(category, keyName, content, 4) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var slug by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("# Istruzioni\n1. ") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = slug, onValueChange = { slug = it }, label = { Text("Slug (es. kotlin_review)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titolo") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Breve descrizione") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("Istruzioni") }, maxLines = 4, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = { Button(onClick = { onSave(slug, title, description, instructions) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
