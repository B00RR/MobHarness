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
        recentContextDigest: String = "",
        userPrompt: String = "",
        contextBudgetTokens: Int = 8192
    ): String {
        val vault = repository.readVault()
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN).format(Date())
        val accessibilityReady = MobHarnAccessibilityService.isConnected.value

        val isLongCtx = contextBudgetTokens >= 14336
        val isCompactCtx = contextBudgetTokens <= 4096
        val soulBudget = when {
            isLongCtx -> 1050
            isCompactCtx -> 650
            else -> 880
        }
        val userBudget = when {
            isLongCtx -> 900
            isCompactCtx -> 420
            else -> 650
        }
        val maxRelevantMemories = when {
            isLongCtx -> 10
            isCompactCtx -> 4
            else -> 6
        }
        val maxSkills = when {
            isLongCtx -> 10
            isCompactCtx -> 5
            else -> 7
        }
        val workspaceBudget = when {
            isLongCtx -> 900
            isCompactCtx -> 350
            else -> 550
        }

        // Just-in-Time On-Device Memory RAG: combine core high-importance profile entries with BM25 query hits
        val allMemories = repository.getAllMemories()
        val selectedMemories = linkedMapOf<Long, com.example.data.db.MemoryEntryEntity>()
        if (allMemories.isNotEmpty()) {
            // 1. Always keep core user identity / top preferences (importance >= 4)
            allMemories
                .filter { it.importance >= 4 && (it.category == "USER_PROFILE" || it.category == "PREFERENCE") }
                .sortedByDescending { it.updatedAt }
                .take(if (isCompactCtx) 3 else 5)
                .forEach { selectedMemories[it.id] = it }

            // 2. Inject BM25 top matches specifically relevant to this user prompt
            if (userPrompt.isNotBlank()) {
                ContextAndMemoryEngine.rankMemories(
                    query = userPrompt,
                    memories = allMemories,
                    limit = maxRelevantMemories
                ).forEach { scored ->
                    selectedMemories[scored.entry.id] = scored.entry
                }
            }

            // 3. Fill remaining slots with most recent important facts if prompt had few keyword hits
            if (selectedMemories.size < maxRelevantMemories) {
                allMemories
                    .sortedWith(compareByDescending<com.example.data.db.MemoryEntryEntity> { it.importance }.thenByDescending { it.updatedAt })
                    .forEach { entry ->
                        if (selectedMemories.size < maxRelevantMemories) {
                            selectedMemories.putIfAbsent(entry.id, entry)
                        }
                    }
            }
        }

        return buildString {
            appendLine(vault.soulMd.trim().take(soulBudget))
            appendLine()
            appendLine("## REGOLE CRITICHE SUI TOOL")
            appendLine("- Data/ora: $now")
            appendLine("- Il modello gira in locale, ma HAI ACCESSO REALE A INTERNET e agli strumenti Android tramite le funzioni registrate.")
            appendLine("- ESECUZIONE IMMEDIATA: NON rispondere mai solo 'Sì, posso farlo'. Quando l'utente chiede un'azione (ricerca web, aprire un'app con `launch_app`, sveglia/timer, telemetria, codice), INVOCA SUBITO il tool corrispondente.")
            appendLine("- Non inventare risultati e non scrivere chiamate di funzione come semplice testo nella risposta: usa il meccanismo di chiamata tool e rispondi in italiano con i dati reali.")
            appendLine()
            appendLine("## APPRENDIMENTO CONTINUO (MEMORIA & SKILL)")
            appendLine("- SEGNARE E RICORDARE: appena l'utente condivide fatti personali (nome, città, lavoro, gusti, dispositivi, progetti), preferenze di stile, oppure dice 'ricordati', 'segna', 'annota', 'tieni a mente', chiama SUBITO `save_memory` (categorie: USER_PROFILE, PREFERENCE, FACT, PROJECT, CODING_CONVENTION) con una chiave `snake_case` chiara.")
            appendLine("- RECUPERO MEMORIA: se l'utente fa riferimento a cose dette in passato o chiede cosa ricordi di lui, usa `search_memory` o `search_past_sessions` prima di rispondere.")
            appendLine("- USARE LE SKILL: controlla sempre l'elenco SKILL qui sotto; se una skill è pertinente alla richiesta, carica le sue istruzioni con `load_skill` e seguile.")
            appendLine("- CREARE E MIGLIORARE SKILL: quando impari un nuovo flusso riutilizzabile, risolvi un'attività in più passaggi o l'utente ti insegna come svolgere un compito ricorrente ('impara a...', 'ogni volta che...', 'crea una skill'), salva o aggiorna la procedura con `save_skill` così da migliorare con l'uso.")

            if (vault.userMd.isNotBlank()) {
                appendLine()
                appendLine("## UTENTE (USER.md)")
                appendLine(vault.userMd.trim().take(userBudget))
            }

            if (selectedMemories.isNotEmpty()) {
                appendLine()
                appendLine("## MEMORIA PERSISTENTE RILEVANTE")
                selectedMemories.values.take(maxRelevantMemories + 4).forEach { m ->
                    appendLine("- [${m.category}] ${m.keyName}: ${m.content.take(if (isLongCtx) 240 else 160)}")
                }
            } else if (vault.memoryMd.isNotBlank()) {
                appendLine()
                appendLine("## MEMORIA PERSISTENTE (MEMORY.md)")
                appendLine(vault.memoryMd.trim().take(if (isLongCtx) 1400 else 800))
            }

            // Rank skills dynamically so large skill libraries always surface the best matches
            val skills = repository.getEnabledSkills()
            if (skills.isNotEmpty()) {
                val topSkills = ContextAndMemoryEngine.rankSkillsForPrompt(userPrompt, skills, limit = maxSkills)
                appendLine()
                appendLine("## SKILL APPRESE (usa load_skill o aggiorna con save_skill)")
                topSkills.forEach { s ->
                    val usage = if (s.usageCount > 0) " [usata ${s.usageCount}x]" else ""
                    appendLine("- /${s.slug}: ${s.description}$usage")
                }
            }

            if (routedGroups.isEmpty() || "coding" in routedGroups) {
                val projectMap = repository.workspace.buildCompactProjectMap(activeProject)
                appendLine()
                appendLine("## WORKSPACE ($activeProject)")
                appendLine(projectMap.take(workspaceBudget))
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
                appendLine("## RIEPILOGO SESSIONE (AUTO-COMPATTATO)")
                appendLine(recentContextDigest)
            }
        }.trim()
    }
}
