package com.example

import com.example.agent.ContextAndMemoryEngine
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
}
