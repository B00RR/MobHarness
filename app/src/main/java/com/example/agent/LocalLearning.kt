package com.example.agent

import com.example.agent.tools.ToolCallTrace
import com.example.data.db.AgentSkillEntity

internal object LocalLearning {
    const val verifiedRecents = "Pannello Recenti visibile e verificato. Non indica il numero totale di app attive."

    fun suggest(userPrompt: String, traces: List<ToolCallTrace>): AgentSkillEntity? {
        if (traces.any { it.status != "success" }) return null
        val correction = Regex("""(?:correzione|impara questa procedura):\s*([^|\n]{2,64})\s*\|\s*(.{4,500})""", RegexOption.IGNORE_CASE).matchEntire(userPrompt.trim())
        if (correction != null) {
            val title = correction.groupValues[1].trim()
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            if (slug.isEmpty()) return null
            return AgentSkillEntity(slug = slug, title = title, description = title, instructionsMd = correction.groupValues[2].trim())
        }
        // Only accept this native postcondition, never text copied from the screen or the assistant.
        if (traces.none { it.toolName == "phone_global_action" && it.category == "phone_use" &&
                it.argumentsSummary in setOf("global recents", "global recenti") && it.resultSummary == verifiedRecents }) return null
        return AgentSkillEntity(slug = "pannello_recenti", title = "Aprire le app recenti", description = "Visualizzare la panoramica delle applicazioni recenti",
            instructionsMd = "Per mostrare le app recenti, usa phone_global_action con recents una sola volta. Conferma l’apertura solo se il servizio verifica il pannello. Altrimenti fermati; non dedurre quali app sono attive.")
    }
}
