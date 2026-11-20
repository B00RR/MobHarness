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
    fun phoneAvailableWithoutKeywordsOnlyWhenEnabled() {
        val prompt = "Quali app ho aperte?"
        assertEquals(setOf("phone_use"), routeToolGroupsForPrompt(prompt, allEnabled, accessibilityConnected = true))
        assertFalse("phone_use" in routeToolGroupsForPrompt(prompt, allEnabled - "phone_use", accessibilityConnected = true))
        assertFalse("phone_use" in routeToolGroupsForPrompt(prompt, allEnabled, accessibilityConnected = false))
        assertEquals(setOf("web_calc", "coding", "phone_use", "data_tools"), routeToolGroupsForPrompt("cerca il file", allEnabled, accessibilityConnected = true))
    }

    @Test
    fun disconnectedDateTimeAndPlainChatUseZeroTools() {
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("che giorno è oggi?", allEnabled, accessibilityConnected = false))
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("che ore sono?", allEnabled, accessibilityConnected = false))
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("non mi hai risposto, hai solo pensato ma non mi hai risposto", allEnabled, accessibilityConnected = false))
        assertEquals(emptySet<String>(), routeToolGroupsForPrompt("Ciao! Spiegami brevemente cos'è la fotosintesi.", allEnabled, accessibilityConnected = false))
    }

    @Test
    fun factualWebSearchRoutesWebCalcOnly() {
        val groups = routeToolGroupsForPrompt("puoi cercare su internet, quanti abitanti ha Cardito (NA)?", allEnabled, accessibilityConnected = true)
        assertTrue("web_calc" in groups)
        assertFalse("memory" in groups)
        assertFalse("telemetry" in groups)
    }

    @Test
    fun memoryAndSkillsRouteMemoryGroup() {
        val memGroups = routeToolGroupsForPrompt("ricordati che preferisco risposte brevi in italiano", allEnabled, accessibilityConnected = true)
        assertTrue("memory" in memGroups)

        val skillGroups = routeToolGroupsForPrompt("crea una skill per riassumere articoli", allEnabled, accessibilityConnected = true)
        assertTrue("memory" in skillGroups)
    }

    @Test
    fun androidActionsRouteCorrectly() {
        val groups = routeToolGroupsForPrompt("apri YouTube per favore", allEnabled, accessibilityConnected = true)
        assertTrue("android_actions" in groups)
    }
}
