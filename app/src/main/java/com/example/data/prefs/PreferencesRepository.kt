package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.hermesDataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_edge_prefs")

enum class ToolGroup(val id: String, val label: String, val subtitle: String) {
    CODING("coding", "Coding & Sandbox", "Read/patch multi-file, grep, sandbox JS ES6+ e SQLite"),
    MEMORY("memory", "Memoria & Skill", "SOUL/USER/MEMORY, ricerca FTS sessioni, skill procedurali"),
    ANDROID_ACTIONS("android_actions", "Azioni Android", "Sveglie, eventi calendario, mappe, apertura app e URL"),
    WEB_CALC("web_calc", "Web & Calcolo", "Ricerca web, lettura pagine URL, calcolatrice, JSON/Regex"),
    VISION("vision", "Visione Multimodale", "Analisi immagini da fotocamera e Android Photo Picker"),
    AUTOMATIONS("automations", "Automazioni Cron", "Pianificazione task in background tramite WorkManager"),
    TELEMETRY("telemetry", "Telemetria & Sensori", "Batteria, rete, RAM, appunti, sensori e posizione"),
    SELF_EVOLVE("self_evolve", "Auto-Evoluzione Harness", "Crea/esegue Custom Tool JS permanenti, evolve SOUL.md e parametri"),
    PHONE_USE("phone_use", "Controllo Schermo & Pixel", "Legge lo schermo (Accessibilità), esegue tap, digita testo e scroll")
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val activeModelId: Long = -1L,
    val requireApprovalForExternalActions: Boolean = true,
    val activeProjectName: String = "starter_web",
    val enabledToolGroups: Set<String> = ToolGroup.entries.map { it.id }.toSet()
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val ACTIVE_MODEL_ID = longPreferencesKey("active_model_id")
        val REQUIRE_APPROVAL = booleanPreferencesKey("require_approval_external")
        val ACTIVE_PROJECT = stringPreferencesKey("active_project_name")
        val ENABLED_TOOL_GROUPS = stringSetPreferencesKey("enabled_tool_groups")
    }

    val preferences: Flow<AppPreferences> = context.hermesDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.SYSTEM)
        AppPreferences(
            themeMode = themeMode,
            useDynamicColor = prefs[Keys.USE_DYNAMIC_COLOR] ?: true,
            activeModelId = prefs[Keys.ACTIVE_MODEL_ID] ?: -1L,
            requireApprovalForExternalActions = prefs[Keys.REQUIRE_APPROVAL] ?: true,
            activeProjectName = prefs[Keys.ACTIVE_PROJECT] ?: "starter_web",
            enabledToolGroups = prefs[Keys.ENABLED_TOOL_GROUPS]
                ?: ToolGroup.entries.map { it.id }.toSet()
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.hermesDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.USE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setActiveModelId(id: Long) {
        context.hermesDataStore.edit { it[Keys.ACTIVE_MODEL_ID] = id }
    }

    suspend fun setRequireApproval(require: Boolean) {
        context.hermesDataStore.edit { it[Keys.REQUIRE_APPROVAL] = require }
    }

    suspend fun setActiveProject(projectName: String) {
        context.hermesDataStore.edit { it[Keys.ACTIVE_PROJECT] = projectName }
    }

    suspend fun toggleToolGroup(groupId: String, enabled: Boolean) {
        context.hermesDataStore.edit { prefs ->
            val current = (prefs[Keys.ENABLED_TOOL_GROUPS]
                ?: ToolGroup.entries.map { it.id }.toSet()).toMutableSet()
            if (enabled) current.add(groupId) else current.remove(groupId)
            prefs[Keys.ENABLED_TOOL_GROUPS] = current
        }
    }
}
