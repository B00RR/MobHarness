package com.example.data.vault

import android.content.Context
import com.example.data.db.AgentSkillEntity
import com.example.data.db.MemoryEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VaultDocuments(
    val soulMd: String,
    val userMd: String,
    val memoryMd: String,
    val estimatedSystemTokens: Int
)

class MarkdownVaultManager(context: Context) {

    private val vaultDir: File = File(context.filesDir, "mobharn_vault").apply { mkdirs() }
    private val skillsDir: File = File(vaultDir, "skills").apply { mkdirs() }

    private val soulFile = File(vaultDir, "SOUL.md")
    private val userFile = File(vaultDir, "USER.md")
    private val memoryFile = File(vaultDir, "MEMORY.md")

    suspend fun ensureInitialized(memories: List<MemoryEntryEntity>, skills: List<AgentSkillEntity>) =
        withContext(Dispatchers.IO) {
            if (!soulFile.exists()) {
                soulFile.writeText(DEFAULT_SOUL_MD)
            }
            if (!userFile.exists() || !memoryFile.exists()) {
                syncFromMemories(memories)
            }
            syncSkillsToDisk(skills)
        }

    suspend fun readVault(): VaultDocuments = withContext(Dispatchers.IO) {
        val soul = if (soulFile.exists()) soulFile.readText() else DEFAULT_SOUL_MD
        val user = if (userFile.exists()) userFile.readText() else DEFAULT_USER_MD
        val mem = if (memoryFile.exists()) memoryFile.readText() else DEFAULT_MEMORY_MD
        val approxTokens = ((soul.length + user.length + mem.length) / 3.8).toInt()
        VaultDocuments(soul, user, mem, approxTokens)
    }

    suspend fun writeSoulMd(content: String) = withContext(Dispatchers.IO) {
        soulFile.writeText(content)
    }

    suspend fun writeUserMd(content: String) = withContext(Dispatchers.IO) {
        userFile.writeText(content)
    }

    suspend fun writeMemoryMd(content: String) = withContext(Dispatchers.IO) {
        memoryFile.writeText(content)
    }

    suspend fun syncFromMemories(memories: List<MemoryEntryEntity>) = withContext(Dispatchers.IO) {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val userEntries = memories.filter {
            it.category == "USER_PROFILE" || it.category == "PREFERENCE"
        }
        val userBuilder = StringBuilder().apply {
            appendLine("# USER.md — Profilo & Preferenze Utente")
            appendLine()
            appendLine("_Aggiornato automaticamente da MobHarn (${dateFmt.format(Date())})_")
            appendLine()
            if (userEntries.isEmpty()) {
                appendLine("- Nessun fatto utente registrato ancora. L'agente memorizzerà preferenze e contesto automaticamente.")
            } else {
                userEntries.forEach { entry ->
                    appendLine("- **${entry.keyName}** `[${entry.category}]`: ${entry.content}")
                }
            }
        }
        userFile.writeText(userBuilder.toString())

        val globalEntries = memories.filterNot {
            it.category == "USER_PROFILE" || it.category == "PREFERENCE"
        }
        val memBuilder = StringBuilder().apply {
            appendLine("# MEMORY.md — Memoria Persistente & Convenzioni")
            appendLine()
            appendLine("_Aggiornato automaticamente da MobHarn (${dateFmt.format(Date())})_")
            appendLine()
            if (globalEntries.isEmpty()) {
                appendLine("- Nessuna memoria globale ancora salvata.")
            } else {
                globalEntries.groupBy { it.category }.forEach { (cat, list) ->
                    appendLine("## $cat")
                    list.forEach { entry ->
                        appendLine("- **${entry.keyName}** (priorità ${entry.importance}/5): ${entry.content}")
                    }
                    appendLine()
                }
            }
        }
        memoryFile.writeText(memBuilder.toString())
    }

    suspend fun syncSkillsToDisk(skills: List<AgentSkillEntity>) = withContext(Dispatchers.IO) {
        skills.forEach { skill ->
            val safeSlug = skill.slug.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(skillsDir, "$safeSlug.md")
            val content = buildString {
                appendLine("---")
                appendLine("slug: ${skill.slug}")
                appendLine("title: ${skill.title}")
                appendLine("description: ${skill.description}")
                appendLine("enabled: ${skill.enabled}")
                appendLine("---")
                appendLine()
                appendLine(skill.instructionsMd)
            }
            file.writeText(content)
        }
    }

    suspend fun exportBundleMarkdown(
        outputStream: OutputStream,
        skills: List<AgentSkillEntity>
    ) = withContext(Dispatchers.IO) {
        val docs = readVault()
        val bundle = buildString {
            appendLine("# MobHarn — Export Completo Memoria & Skill")
            appendLine("_Generato il ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}_")
            appendLine()
            appendLine("---")
            appendLine(docs.soulMd)
            appendLine()
            appendLine("---")
            appendLine(docs.userMd)
            appendLine()
            appendLine("---")
            appendLine(docs.memoryMd)
            appendLine()
            appendLine("---")
            appendLine("# SKILLS (${skills.size})")
            skills.forEach { skill ->
                appendLine("## /${skill.slug} — ${skill.title}")
                appendLine("> ${skill.description}")
                appendLine()
                appendLine(skill.instructionsMd)
                appendLine()
            }
        }
        outputStream.use { it.write(bundle.toByteArray(Charsets.UTF_8)) }
    }

    companion object {
        val DEFAULT_SOUL_MD = """
            # SOUL.md — Identità di MobHarn

            Sei **MobHarn**, un agente AI autonomo e coding partner su Android (Gemma 4 LiteRT-LM) dotato di strumenti reali (ricerca Internet, coding sandbox, memoria persistente e azioni di sistema).

            ## Principi Operativi
            1. **Usa sempre i tool reali**: per ricerche su Internet o notizie chiama `web_search` / `fetch_webpage`; per calcoli usa `calculate_expression`; per batteria/sensori usa `get_device_telemetry`; per codice usa il workspace e la sandbox JS/SQL. Non dire mai che non hai accesso a Internet o ai dati reali quando hai lo strumento attivo.
            2. **Memoria attiva**: salva preferenze e fatti importanti con `save_memory` e cerca il passato con `search_memory` / `search_past_sessions`.
            3. **Stile**: rispondi in italiano, chiaro, sintetico e trasparente sui risultati dei tool.
        """.trimIndent()

        val DEFAULT_USER_MD = """
            # USER.md — Profilo & Preferenze Utente

            - **Dispositivo**: Google Pixel 10 Pro XL (inferenza on-device LiteRT-LM Gemma 4)
        """.trimIndent()

        val DEFAULT_MEMORY_MD = """
            # MEMORY.md — Memoria Persistente & Convenzioni

            - Ambiente configurato: MobHarn vault locale + Coding Workspace sandboxed.
        """.trimIndent()
    }
}
