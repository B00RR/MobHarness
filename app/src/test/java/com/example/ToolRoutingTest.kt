package com.example

import com.example.agent.tools.routeToolGroupsForPrompt
import com.example.data.prefs.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoutingTest {

    private val allEnabled = ToolGroup.entries.map { it.id }.toSet()

    @Test
    fun pureDateTimeAndPlainChatUseZeroTools() {
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("che giorno è oggi?", allEnabled))
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("che ore sono?", allEnabled))
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("non mi hai risposto, hai solo pensato ma non mi hai risposto", allEnabled))
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("Ciao! Spiegami brevemente cos'è la fotosintesi.", allEnabled))
    }

    @Test
    fun factualWebSearchRoutesWebCalcOnly() {
        val groups = routeToolGroupsForPrompt("puoi cercare su internet, quanti abitanti ha Cardito (NA)?", allEnabled)
        assertTrue("web_calc" in groups)
        assertFalse("memory" in groups)
        assertFalse("telemetry" in groups)
    }

    @Test
    fun memoryAndSkillsRouteMemoryGroup() {
        val memGroups = routeToolGroupsForPrompt("ricordati che preferisco risposte brevi in italiano", allEnabled)
        assertTrue("memory" in memGroups)

        val skillGroups = routeToolGroupsForPrompt("crea una skill per riassumere articoli", allEnabled)
        assertTrue("memory" in skillGroups)
    }

    @Test
    fun androidActionsRouteCorrectly() {
        val groups = routeToolGroupsForPrompt("apri YouTube per favore", allEnabled)
        assertTrue("android_actions" in groups)
    }
}
