package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
        viewModel.reloadVault()
        viewModel.events.collect { onShowMessage(it) }
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            title = {
                Column {
                    Text("Memoria Hermes", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "~${uiState.estimatedTokens} token nel prompt • ${memories.size} fatti • ${skills.size} skill",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { exportMdLauncher.launch("hermes_memory_bundle.md") },
                    modifier = Modifier.testTag("export_markdown_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.cd_export_markdown))
                }
                if (selectedTab == 1) {
                    IconButton(
                        onClick = { showAddMemoryDialog = true },
                        modifier = Modifier.testTag("add_memory_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Aggiungi memoria")
                    }
                } else if (selectedTab == 2) {
                    IconButton(
                        onClick = { showAddSkillDialog = true },
                        modifier = Modifier.testTag("add_skill_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Aggiungi skill")
                    }
                }
            }
        )

        val tabs = listOf("Vault MD", "Fatti (${memories.size})", "Skill (${skills.size})")
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
                    modifier = Modifier.testTag("memory_tab_$idx")
                ) {
                    Text(label)
                }
            }
        }

        when (selectedTab) {
            0 -> {
                val docNames = listOf("SOUL.md", "USER.md", "MEMORY.md")
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
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
                        Button(
                            onClick = {
                                when (selectedVaultDoc) {
                                    0 -> viewModel.saveSoulMd()
                                    1 -> viewModel.saveUserMd()
                                    else -> viewModel.saveMemoryMd()
                                }
                            },
                            modifier = Modifier.testTag("save_vault_doc_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Salva")
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
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 12.dp)
                            .testTag("vault_markdown_editor")
                    )
                }
            }
            1 -> {
                val categories = listOf("ALL", "USER_PROFILE", "PREFERENCE", "FACT", "PROJECT", "CODING_CONVENTION")
                val filtered = remember(memories, uiState.selectedCategory, uiState.searchQuery) {
                    memories.filter { m ->
                        (uiState.selectedCategory == "ALL" || m.category == uiState.selectedCategory) &&
                            (uiState.searchQuery.isBlank() ||
                                m.keyName.contains(uiState.searchQuery, true) ||
                                m.content.contains(uiState.searchQuery, true))
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            placeholder = { Text("Filtra fatti persistenti...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(categories) { cat ->
                                FilterChip(
                                    selected = uiState.selectedCategory == cat,
                                    onClick = { viewModel.setCategoryFilter(cat) },
                                    label = { Text(cat) }
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.id }) { entry ->
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${entry.keyName} • ${entry.category} • P${entry.importance}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { viewModel.deleteMemory(entry.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Elimina fatto", tint = StatusError)
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(skills, key = { it.id }) { skill ->
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Column {
                                            Text("/${skill.slug} — ${skill.title}", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "Usata ${skill.usageCount} volte",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = skill.enabled,
                                        onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                                    )
                                }
                                Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        skill.instructionsMd,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        maxLines = 5,
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

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit
) {
    var category by remember { mutableStateOf("FACT") }
    var keyName by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val categories = listOf("USER_PROFILE", "PREFERENCE", "FACT", "PROJECT", "CODING_CONVENTION")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi Memoria Persistente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it },
                    label = { Text("Chiave (es. stack_preferito)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Contenuto da ricordare") },
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(category, keyName, content, 4) }) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
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
        title = { Text("Nuova Skill Procedurale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it },
                    label = { Text("Slug (es. kotlin_review)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titolo") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Quando usarla (breve)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Istruzioni Markdown") },
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(slug, title, description, instructions) }) { Text("Salva Skill") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
