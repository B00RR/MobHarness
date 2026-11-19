package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AgentSkillEntity
import com.example.data.db.MemoryEntryEntity
import com.example.data.vault.VaultDocuments
import com.example.di.HermesAppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryUiState(
    val soulMd: String = "",
    val userMd: String = "",
    val memoryMd: String = "",
    val estimatedTokens: Int = 0,
    val selectedCategory: String = "ALL",
    val searchQuery: String = ""
)

class MemoryViewModel(
    private val appContext: Context,
    private val container: HermesAppContainer
) : ViewModel() {

    private val repo = container.repository

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val memories: StateFlow<List<MemoryEntryEntity>> = repo.memoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skills: StateFlow<List<AgentSkillEntity>> = repo.skillsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        reloadVault()
        viewModelScope.launch {
            memories.collect { reloadVault() }
        }
    }

    fun reloadVault() {
        viewModelScope.launch {
            val docs: VaultDocuments = repo.readVault()
            _uiState.update {
                it.copy(
                    soulMd = docs.soulMd,
                    userMd = docs.userMd,
                    memoryMd = docs.memoryMd,
                    estimatedTokens = docs.estimatedSystemTokens
                )
            }
        }
    }

    fun updateSoulDraft(text: String) = _uiState.update { it.copy(soulMd = text) }
    fun updateUserDraft(text: String) = _uiState.update { it.copy(userMd = text) }
    fun updateMemoryDraft(text: String) = _uiState.update { it.copy(memoryMd = text) }

    fun saveSoulMd() {
        viewModelScope.launch {
            repo.saveSoulMd(_uiState.value.soulMd)
            reloadVault()
            _events.tryEmit("SOUL.md salvato e pronto per il prossimo turno.")
        }
    }

    fun saveUserMd() {
        viewModelScope.launch {
            repo.saveUserMd(_uiState.value.userMd)
            reloadVault()
            _events.tryEmit("USER.md salvato.")
        }
    }

    fun saveMemoryMd() {
        viewModelScope.launch {
            repo.saveMemoryMd(_uiState.value.memoryMd)
            reloadVault()
            _events.tryEmit("MEMORY.md salvato.")
        }
    }

    fun setCategoryFilter(category: String) = _uiState.update { it.copy(selectedCategory = category) }
    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun addMemory(category: String, keyName: String, content: String, importance: Int) {
        if (keyName.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            repo.upsertMemoryAndSync(category, keyName, content, importance)
            reloadVault()
            _events.tryEmit("Memoria '$keyName' salvata e sincronizzata in Markdown.")
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            repo.deleteMemoryAndSync(id)
            reloadVault()
            _events.tryEmit("Voce rimossa dalla memoria persistente.")
        }
    }

    fun saveSkill(slug: String, title: String, description: String, instructionsMd: String) {
        if (slug.isBlank() || title.isBlank()) return
        viewModelScope.launch {
            repo.upsertSkill(slug, title, description, instructionsMd)
            _events.tryEmit("Skill '/$slug' salvata nella libreria.")
        }
    }

    fun toggleSkill(id: Long, enabled: Boolean) {
        viewModelScope.launch { repo.setSkillEnabled(id, enabled) }
    }

    fun deleteSkill(id: Long) {
        viewModelScope.launch {
            repo.deleteSkill(id)
            _events.tryEmit("Skill eliminata.")
        }
    }

    fun exportMarkdownBundle(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { os ->
                    repo.vault.exportBundleMarkdown(os, skills.value)
                } ?: error("Stream di esportazione non disponibile")
            }.onSuccess {
                _events.tryEmit("Bundle Markdown (SOUL/USER/MEMORY/Skills) esportato!")
            }.onFailure {
                _events.tryEmit("Errore esportazione Markdown: ${it.message}")
            }
        }
    }
}
