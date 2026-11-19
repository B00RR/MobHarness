package com.example.agent

import com.example.agent.phoneuse.HermesAccessibilityService
import com.example.data.HermesRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemPromptAssembler(private val repository: HermesRepository) {

    suspend fun assemble(activeProject: String): String {
        val vault = repository.readVault()
        val skills = repository.getEnabledSkills()
        val customTools = repository.getEnabledCustomTools()
        val projectMap = repository.workspace.buildCompactProjectMap(activeProject)
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN).format(Date())
        val accessibilityReady = HermesAccessibilityService.isConnected.value

        return buildString {
            appendLine(vault.soulMd.trim())
            appendLine()
            appendLine("## CONTESTO TEMPORALE & DISPOSITIVO")
            appendLine("Data e ora locale: $now")
            appendLine("Runtime: LiteRT-LM on-device su Android Pixel (100% locale)")
            appendLine(
                "Controllo Schermo (Accessibilità): " +
                    if (accessibilityReady) "ATTIVO (puoi usare read_screen_ui, tap_screen_text, type_screen_text, scroll_screen, phone_global_action)"
                    else "NON ATTIVO (l'utente può attivarlo da Studio → Strumenti → Accessibilità)"
            )
            appendLine()
            appendLine("## PROFILO UTENTE (USER.md)")
            appendLine(vault.userMd.trim().take(2400))
            appendLine()
            appendLine("## MEMORIA PERSISTENTE (MEMORY.md)")
            appendLine(vault.memoryMd.trim().take(3200))
            appendLine()
            appendLine("## INDICE SKILL PROCEDURALI (carica con load_skill quando pertinente)")
            if (skills.isEmpty()) {
                appendLine("- Nessuna skill attiva.")
            } else {
                skills.forEach { s ->
                    appendLine("- `/${s.slug}` (${s.title}): ${s.description}")
                }
            }
            appendLine()
            appendLine("## CUSTOM JS TOOLS DELL'HARNESS (eseguibili con run_custom_tool o creabili con create_custom_tool)")
            if (customTools.isEmpty()) {
                appendLine("- Nessun custom tool registrato. Puoi crearne di nuovi con `create_custom_tool` per estendere il tuo harness.")
            } else {
                customTools.forEach { t ->
                    appendLine("- `${t.name}`: ${t.description} (${t.parametersDoc})")
                }
            }
            appendLine()
            appendLine("## CODING WORKSPACE ATTIVO")
            appendLine(projectMap)
        }.trim()
    }
}
