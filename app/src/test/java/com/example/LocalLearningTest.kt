package com.example

import com.example.agent.LocalLearning
import com.example.agent.tools.ToolCallTrace
import org.junit.Assert.*
import org.junit.Test

class LocalLearningTest {
    private fun trace(tool: String, result: String, status: String = "success") =
        ToolCallTrace(toolName = tool, category = "phone_use", argumentsSummary = "global recents", resultSummary = result, status = status)

    @Test fun onlyTheVerifiedNativeOutcomeCreatesAnAutomaticDraft() {
        val proof = "Pannello Recenti visibile e verificato. Non indica il numero totale di app attive."
        assertNotNull(LocalLearning.suggest("apri i recenti", listOf(trace("phone_global_action", proof))))
        assertNull(LocalLearning.suggest("apri i recenti", listOf(trace("read_screen_ui", proof))))
        assertNull(LocalLearning.suggest("apri i recenti", listOf(trace("phone_global_action", "Azione inviata"))))
        assertNull(LocalLearning.suggest("apri i recenti", listOf(trace("phone_global_action", proof, "denied"))))
    }
    @Test fun correctionComesFromTheUserAndHasAStableKey() {
        val draft = requireNotNull(LocalLearning.suggest("Correzione: ordine date | Confronta mese e anno prima del giorno.", emptyList()))
        assertEquals("ordine_date", draft.slug)
        assertEquals("Confronta mese e anno prima del giorno.", draft.instructionsMd)
        assertNull(LocalLearning.suggest("Leggi questo testo", emptyList()))
    }
}
