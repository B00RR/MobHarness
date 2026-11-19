package com.example.agent

import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.data.MobHarnRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemPromptAssembler(private val repository: MobHarnRepository) {

    suspend fun assemble(
        activeProject: String,
        routedGroups: Set<String> = emptySet(),
        recentContextDigest: String = ""
    ): String {
        val vault = repository.readVault()
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN).format(Date())
        val accessibilityReady = MobHarnAccessibilityService.isConnected.value

        return buildString {
            appendLine(vault.soulMd.trim().take(900))
            appendLine()
            appendLine("## REGOLE CRITICHE SUI TOOL")
            appendLine("- Data/ora: $now")
            appendLine("- Il modello gira sul Pixel, ma HAI ACCESSO REALE A INTERNET e agli strumenti Android tramite le funzioni registrate.")
            appendLine("- REGOLA DI ESECUZIONE IMMEDIATA: NON rispondere mai solo 'Sì, posso farlo' o 'Posso aprire l'app'. Quando l'utente chiede un'azione o chiede se puoi farla (cercare su internet, aprire un'app con `launch_app`, impostare sveglia/timer, leggere telemetria/schermo, eseguire codice), INVOCA SUBITO la funzione tool corrispondente nello stesso turno.")
            appendLine("- Non ripetere risposte precedenti e non inventare risultati: esegui il tool e rispondi in modo conciso con il risultato reale.")

            if (vault.userMd.isNotBlank()) {
                appendLine()
                appendLine("## UTENTE")
                appendLine(vault.userMd.trim().take(500))
            }
            if (vault.memoryMd.isNotBlank()) {
                appendLine()
                appendLine("## MEMORIA")
                appendLine(vault.memoryMd.trim().take(700))
            }

            if (routedGroups.isEmpty() || "coding" in routedGroups) {
                val projectMap = repository.workspace.buildCompactProjectMap(activeProject)
                appendLine()
                appendLine("## WORKSPACE ($activeProject)")
                appendLine(projectMap.take(500))
            }

            if (routedGroups.isEmpty() || "memory" in routedGroups) {
                val skills = repository.getEnabledSkills()
                if (skills.isNotEmpty()) {
                    appendLine()
                    appendLine("## SKILL")
                    skills.take(5).forEach { s ->
                        appendLine("- /${s.slug}: ${s.description}")
                    }
                }
            }

            if (routedGroups.isEmpty() || "self_evolve" in routedGroups) {
                val customTools = repository.getEnabledCustomTools()
                if (customTools.isNotEmpty()) {
                    appendLine()
                    appendLine("## CUSTOM JS TOOLS")
                    customTools.take(6).forEach { t ->
                        appendLine("- ${t.name}: ${t.description}")
                    }
                }
            }

            if ("phone_use" in routedGroups) {
                appendLine()
                appendLine("## CONTROLLO SCHERMO")
                appendLine(
                    if (accessibilityReady) "Accessibilità ATTIVA: puoi usare read_screen_ui, tap_screen_text, type_screen_text, scroll_screen."
                    else "Accessibilità NON attiva: avvisa l'utente di attivarla da Studio → Strumenti."
                )
            }

            if (recentContextDigest.isNotBlank()) {
                appendLine()
                appendLine("## CONTESTO RECENTE DELLA CHAT")
                appendLine(recentContextDigest)
            }
        }.trim()
    }
}
