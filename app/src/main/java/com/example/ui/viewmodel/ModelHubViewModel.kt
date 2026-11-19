package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import com.example.agent.EngineStatus
import com.example.agent.tools.DeviceHardwareSummary
import com.example.data.db.ModelProfileEntity
import com.example.data.prefs.AppPreferences
import com.example.di.MobHarnAppContainer
import com.example.ui.theme.ThemeMode
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

data class ModelHubUiState(
    val hardware: DeviceHardwareSummary? = null,
    val isImporting: Boolean = false,
    val importProgress: Float = 0f,
    val importStatus: String = ""
)

class ModelHubViewModel(
    private val appContext: Context,
    private val container: MobHarnAppContainer
) : ViewModel() {

    private val repo = container.repository
    private val runtime = container.runtimeManager

    private val _uiState = MutableStateFlow(ModelHubUiState())
    val uiState: StateFlow<ModelHubUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val models: StateFlow<List<ModelProfileEntity>> = repo.modelsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<AppPreferences> = repo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val engineStatus: StateFlow<EngineStatus> = runtime.status

    fun refreshHardware() {
        _uiState.update { it.copy(hardware = container.systemActions.getHardwareSummary()) }
    }

    fun importModel(uri: Uri) {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, importProgress = 0.01f, importStatus = "Preparazione importazione...")
            }
            val result = repo.importModelFromUri(uri) { progress, statusText ->
                _uiState.update { it.copy(importProgress = progress, importStatus = statusText) }
            }
            _uiState.update { it.copy(isImporting = false) }
            result.onSuccess { profile ->
                refreshHardware()
                _events.tryEmit("Modello '${profile.displayName}' pronto. Tocca 'Carica in Memoria' per avviarlo.")
            }.onFailure { err ->
                _events.tryEmit("Errore importazione modello: ${err.message}")
            }
        }
    }

    fun loadModel(profile: ModelProfileEntity) {
        viewModelScope.launch {
            repo.prefs.setActiveModelId(profile.id)
            val ecoMode = preferences.value.ecoThermalMode
            val result = runtime.loadModel(profile, ecoThermalMode = ecoMode)
            result.onSuccess {
                refreshHardware()
                _events.tryEmit("${profile.displayName} caricato e pronto in Chat!")
            }.onFailure { err ->
                _events.tryEmit("Errore caricamento LiteRT-LM: ${err.message}")
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            runtime.unloadModel()
            runCatching { Coil.imageLoader(appContext).memoryCache?.clear() }
            refreshHardware()
            _events.tryEmit("Modello scaricato e memoria liberata.")
        }
    }

    fun updateModelProfile(profile: ModelProfileEntity) {
        viewModelScope.launch {
            repo.updateModelProfile(profile)
        }
    }

    fun deleteModel(profile: ModelProfileEntity) {
        viewModelScope.launch {
            val status = engineStatus.value
            if (status is EngineStatus.Ready && status.model.id == profile.id) {
                runtime.unloadModel()
            }
            repo.deleteModelProfile(profile)
            refreshHardware()
            _events.tryEmit("Modello '${profile.displayName}' rimosso.")
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repo.prefs.setThemeMode(mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repo.prefs.setUseDynamicColor(enabled) }
    }

    fun setEcoThermalMode(enabled: Boolean) {
        viewModelScope.launch { repo.prefs.setEcoThermalMode(enabled) }
    }

    fun setAutoUnloadInBackground(enabled: Boolean) {
        viewModelScope.launch { repo.prefs.setAutoUnloadInBackground(enabled) }
    }
}
