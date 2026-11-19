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

        val hasTools = routedGroups.isNotEmpty()
        val hasMemoryTools = "memory" in routedGroups

        return buildString {
            appendLine(vault.soulMd.trim().take(soulBudget))
            appendLine()
            appendLine("Data e ora attuali: $now.")
            appendLine("Rispondi sempre in italiano in modo diretto, accurato e conciso.")

            if (hasTools) {
                appendLine()
                appendLine("## STRUMENTI ATTIVI")
                appendLine("- Hai accesso reale agli strumenti registrati in questo turno (incluso Internet se `web_search` è attivo).")
                appendLine("- Quando la richiesta richiede un dato esterno o un'azione, invoca subito lo strumento appropriato senza fingere di averlo fatto e poi rispondi in italiano usando i risultati reali.")
                appendLine("- Non scrivere mai chiamate di funzione come semplice testo nella risposta.")
            }

            if (hasMemoryTools) {
                appendLine()
                appendLine("## MEMORIA & SKILL")
                appendLine("- Se l'utente condivide fatti personali o chiede di segnare/ricordare qualcosa, salva subito con `save_memory` (USER_PROFILE, PREFERENCE, FACT, PROJECT, CODING_CONVENTION).")
                appendLine("- Per recuperare ricordi o vecchie chat usa `search_memory` o `search_past_sessions`.")
                appendLine("- Per procedure apprese usa `load_skill` oppure crea/aggiorna una procedura riutilizzabile con `save_skill`.")
            }

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

            // Show skills catalog when memory/skills tools are active or prompt mentions a skill
            val skills = repository.getEnabledSkills()
            if (skills.isNotEmpty() && (hasMemoryTools || userPrompt.contains("/"))) {
                val topSkills = ContextAndMemoryEngine.rankSkillsForPrompt(userPrompt, skills, limit = maxSkills)
                appendLine()
                appendLine("## SKILL APPRESE (usa load_skill o aggiorna con save_skill)")
                topSkills.forEach { s ->
                    val usage = if (s.usageCount > 0) " [usata ${s.usageCount}x]" else ""
                    appendLine("- /${s.slug}: ${s.description}$usage")
                }
            }

            if ("coding" in routedGroups) {
                val projectMap = repository.workspace.buildCompactProjectMap(activeProject)
                appendLine()
                appendLine("## WORKSPACE ($activeProject)")
                appendLine(projectMap.take(workspaceBudget))
            }

            if ("self_evolve" in routedGroups) {
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
