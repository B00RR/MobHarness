package com.example

import com.example.agent.ContextAndMemoryEngine
import com.example.agent.PhoneStep
import com.example.agent.phoneStep
import com.example.agent.formatInferenceStats
import com.example.data.db.AgentSkillEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAgentTest {
    private fun skill(name: String, uses: Int = 0, enabled: Boolean = true) =
        AgentSkillEntity(slug = name, title = name, description = "", instructionsMd = "", usageCount = uses, enabled = enabled)

    @Test fun relevantSkillBeatsPopularityEvenInSmallCatalog() {
        val skills = listOf(skill("meteo", 200), skill("spesa"), skill("spesa_lista", enabled = false))
        assertEquals("spesa", ContextAndMemoryEngine.rankSkillsForPrompt("lista della spesa", skills).first().slug)
    }

    @Test fun suggestionRequiresAnEnabledMatch() {
        val options = listOf(skill("nota", 8), skill("spesa", enabled = false))
        assertEquals(emptyList<AgentSkillEntity>(), ContextAndMemoryEngine.rankSkillsForPrompt("spesa", options, matchesOnly = true))
    }

    @Test fun diagnosticsDistinguishPermissionFromPreference() {
        assertEquals(PhoneStep.STUDIO, phoneStep(false, true))
        assertEquals(PhoneStep.SETTINGS, phoneStep(true, false))
        assertEquals(PhoneStep.RECENTS, phoneStep(true, true))
    }

    @Test fun unavailableMetricsAreNeverFabricated() {
        assertEquals(null, formatInferenceStats("risposta", Double.NaN, 0.0, Double.POSITIVE_INFINITY))
        val report = formatInferenceStats("sintesi", 0.25, Double.NaN, 7.5)!!
        org.junit.Assert.assertTrue(report.contains("250,0 ms") && report.contains("n/d") && report.contains("7,5 tok/s"))
    }
}
