package com.example

import com.example.agent.ContextAndMemoryEngine
import com.example.data.db.AgentSkillEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.MemoryEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAndMemoryEngineTest {

    @Test
    fun rankMemories_findsItalianNaturalLanguageQueriesWithAccentsAndStems() {
        val now = System.currentTimeMillis()
        val memories = listOf(
            MemoryEntryEntity(
                id = 1,
                category = "USER_PROFILE",
                keyName = "citta_utente",
                content = "Abito a Cardito in provincia di Napoli",
                importance = 5,
                updatedAt = now
            ),
            MemoryEntryEntity(
                id = 2,
                category = "USER_PROFILE",
                keyName = "lavoro_utente",
                content = "Lavoro come sviluppatore Android e Kotlin",
                importance = 4,
                updatedAt = now - 10_000L
            ),
            MemoryEntryEntity(
                id = 3,
                category = "FACT",
                keyName = "ricetta_pasta",
                content = "Pasta al pomodoro con basilico fresco",
                importance = 2,
                updatedAt = now - 86_400_000L * 30
            )
        )

        val cityHits = ContextAndMemoryEngine.rankMemories("dove abito? quale città?", memories, limit = 3)
        assertTrue(cityHits.isNotEmpty())
        assertEquals("citta_utente", cityHits.first().entry.keyName)

        val jobHits = ContextAndMemoryEngine.rankMemories("che lavoro faccio su android?", memories, limit = 3)
        assertTrue(jobHits.isNotEmpty())
        assertEquals("lavoro_utente", jobHits.first().entry.keyName)
    }

    @Test
    fun buildFtsOrQuery_stripsStopwordsAndBuildsSafePrefixOrQuery() {
        val fts = ContextAndMemoryEngine.buildFtsOrQuery("dove abito e che lavoro faccio su Kotlin?")
        assertTrue(fts.contains("OR"))
        assertTrue(fts.contains("abit*"))
        assertTrue(fts.contains("lavor*"))
        assertTrue(fts.contains("kotli*"))
    }

    @Test
    fun buildContextWindowPlan_autoCompactsLongSessionPreservingFilesToolsAndGoals() {
        val longSession = (1..20).map { i ->
            val isUser = i % 2 == 1
            ChatMessageEntity(
                id = i.toLong(),
                sessionId = 1L,
                role = if (isUser) "user" else "assistant",
                content = if (isUser) {
                    "Richiesta turno $i: aggiorna AppRouter.kt e ottimizza la gestione memoria"
                } else {
                    "Ho modificato AppRouter.kt al turno $i con successo verificando i test."
                },
                toolCallsJson = if (!isUser) """[{"toolName":"patch_workspace_file"}]""" else "[]"
            )
        }

        val plan = ContextAndMemoryEngine.buildContextWindowPlan(
            allPriorMessages = longSession,
            existingDigest = "",
            maxContextTokens = 8192
        )

        assertTrue(plan.didAutoCompact)
        assertEquals(8, plan.hotHistoryPairs.size)
        assertEquals(12, plan.compactedTurnCount)
        assertTrue(plan.updatedSessionDigest.contains("AppRouter.kt"))
        assertTrue(plan.updatedSessionDigest.contains("patch_workspace_file"))
        assertTrue(plan.updatedSessionDigest.contains("Obiettivi/Richieste precedenti"))
    }

    @Test
    fun compactSingleMessage_preservesBothHeadAndTail() {
        val head = "INIZIO_IMPORTANTE: configurazione principale del progetto "
        val middle = "x".repeat(1200)
        val tail = " CONCLUSIONE_CHIAVE: build verificata con successo."
        val compacted = ContextAndMemoryEngine.compactSingleMessage(head + middle + tail, maxChars = 400)

        assertTrue(compacted.length <= 420)
        assertTrue(compacted.contains("INIZIO_IMPORTANTE"))
        assertTrue(compacted.contains("CONCLUSIONE_CHIAVE"))
        assertTrue(compacted.contains("compattato"))
    }

    @Test
    fun rankSkillsForPrompt_surfacesMatchingSkillFirst() {
        val skills = (1..10).map { i ->
            AgentSkillEntity(
                id = i.toLong(),
                slug = "generic_skill_$i",
                title = "Generic Skill $i",
                description = "Procedura generica numero $i",
                instructionsMd = "Step $i",
                usageCount = 1
            )
        } + AgentSkillEntity(
            id = 99L,
            slug = "docker_deploy",
            title = "Deploy Docker Compose",
            description = "Costruisce container Docker e verifica i log del server",
            instructionsMd = "Run docker compose up",
            usageCount = 0
        )

        val ranked = ContextAndMemoryEngine.rankSkillsForPrompt(
            prompt = "Aiutami a fare il deploy con docker e controllare i log",
            skills = skills,
            limit = 5
        )
        assertEquals(5, ranked.size)
        assertEquals("docker_deploy", ranked.first().slug)
    }
}
