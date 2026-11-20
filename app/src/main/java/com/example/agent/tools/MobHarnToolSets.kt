package com.example.agent.tools

import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.agent.automation.AutomationScheduler
import com.example.agent.sandbox.CodeSandboxRunner
import com.example.data.MobHarnRepository
import com.example.data.db.AutomationTaskEntity
import com.example.data.prefs.ToolGroup
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ToolCallTrace(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val category: String,
    val argumentsSummary: String,
    val resultSummary: String = "",
    val durationMs: Long = 0,
    val status: String = "running" // running, success, error, denied
)

data class PendingToolApproval(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val category: String,
    val description: String,
    val argumentsSummary: String,
    val rememberKey: String? = null
)

class AgentApprovalCoordinator(
    private val grants: android.content.SharedPreferences? = null,
    private val unlocked: () -> Boolean = { true }
) {
    private val _pendingApproval = MutableStateFlow<PendingToolApproval?>(null)
    val pendingApproval = _pendingApproval.asStateFlow()
    private val queue = kotlinx.coroutines.sync.Mutex()
    private enum class Choice { ONCE, SAVE, DENY }
    private var response: CompletableDeferred<Choice>? = null
    private var deadline = 0L

    suspend fun requestApproval(toolName: String, category: String, description: String,
        argumentsSummary: String, rememberKey: String? = null): Boolean = queue.withLock {
        if (rememberKey != null && unlocked() && grants?.getBoolean(rememberKey, false) == true) return@withLock true
        val result = CompletableDeferred<Choice>()
        synchronized(this) {
            deadline = System.nanoTime() + 120_000_000_000L
            response = result
            _pendingApproval.value = PendingToolApproval(toolName = toolName, category = category,
                description = description, argumentsSummary = argumentsSummary, rememberKey = rememberKey)
        }
        try {
            when (kotlinx.coroutines.withTimeoutOrNull(120_000L) { result.await() }) {
                Choice.ONCE -> true
                Choice.SAVE -> {
                    grants?.edit()?.putBoolean(rememberKey, true)?.apply()
                    true
                }
                else -> false
            }
        }
        finally { synchronized(this) { response = null; _pendingApproval.value = null } }
    }

    @Synchronized fun resolve(id: String, approved: Boolean, remember: Boolean = false) {
        val request = _pendingApproval.value ?: return
        val answer = response ?: return
        if (request.id != id || System.nanoTime() >= deadline || answer.isCompleted || (approved && !unlocked())) return
        if (remember && (!approved || request.rememberKey == null)) return
        answer.complete(if (remember) Choice.SAVE else if (approved) Choice.ONCE else Choice.DENY)
    }

    @Synchronized fun clearRemembered() { grants?.edit()?.clear()?.apply() }
}

class ToolExecutionMonitor(
    private val repository: MobHarnRepository,
    private val approvalCoordinator: AgentApprovalCoordinator
) {
    @Volatile var activeSessionId: Long? = null
    @Volatile var activeProjectName: String = "starter_web"
    @Volatile var requireApprovalForExternal: Boolean = true
    @Volatile var onLiveTraceUpdate: ((ToolCallTrace) -> Unit)? = null

    @Volatile var onSkillProposal: ((com.example.data.db.AgentSkillEntity) -> Boolean)? = null
    private val turnLock = Any()
    private val turnCallSignatures = mutableListOf<Pair<String, String>>()
    private val turnToolOutputs = mutableListOf<Pair<String, String>>()
    private var turnAttemptCount: Int = 0

    fun beginTurn() {
        synchronized(turnLock) {
            turnCallSignatures.clear()
            turnToolOutputs.clear()
            turnAttemptCount = 0
        }
    }

    fun getTurnOutputs(): List<Pair<String, String>> = synchronized(turnLock) {
        turnToolOutputs.toList()
    }

    fun executeTracked(
        toolName: String,
        category: String,
        argsSummary: String,
        isExternalOrMutating: Boolean = false,
        approvalDescription: String = "",
        exactCommand: List<String>? = null,
        verifyBeforeSaving: (suspend () -> String?)? = null,
        block: suspend () -> String
    ): String = runBlocking {
        val normSig = toolName.lowercase() to argsSummary.trim().lowercase().replace(Regex("\\s+"), " ")
        val duplicateGuardMsg: String? = synchronized(turnLock) {
            val sameExact = turnCallSignatures.any { it == normSig }
            val sameToolCount = turnCallSignatures.count { it.first == normSig.first }
            when {
                sameExact && toolName != "read_screen_ui" -> "Hai già eseguito $toolName con questi stessi parametri. NON richiamare altri tool: rispondi subito all'utente in italiano usando i dati già ottenuti."
                sameToolCount >= 2 && toolName == "web_search" -> "Hai già effettuato 2 ricerche web in questo turno. NON fare altre ricerche: rispondi ora all'utente in italiano con le informazioni raccolte."
                turnCallSignatures.size >= (if (category == ToolGroup.PHONE_USE.id) 12 else 5) || turnAttemptCount >= (if (category == ToolGroup.PHONE_USE.id) 14 else 6) -> "Limite di chiamate tool per turno raggiunto. Rispondi subito all'utente in italiano con i risultati già ottenuti."
                else -> {
                    turnAttemptCount++
                    null
                }
            }
        }
        if (duplicateGuardMsg != null) {
            return@runBlocking duplicateGuardMsg
        }

        val traceId = UUID.randomUUID().toString()
        val start = System.currentTimeMillis()
        onLiveTraceUpdate?.invoke(
            ToolCallTrace(
                id = traceId,
                toolName = toolName,
                category = category,
                argumentsSummary = argsSummary,
                status = "running"
            )
        )

        verifyBeforeSaving?.invoke()?.let { return@runBlocking it }
        if (verifyBeforeSaving != null || (isExternalOrMutating && requireApprovalForExternal)) {
            val granted = approvalCoordinator.requestApproval(
                toolName = toolName,
                category = category,
                description = approvalDescription.ifBlank { "Eseguire lo strumento $toolName?" },
                argumentsSummary = argsSummary,
                rememberKey = exactCommand?.let {
                    val encoded = JSONArray(listOf("v1", category, toolName) + it).toString().toByteArray(Charsets.UTF_8)
                    java.security.MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { byte -> "%02x".format(byte) }
                }
            )
            if (!granted) {
                val msg = "Azione rifiutata dall'utente."
                val elapsed = System.currentTimeMillis() - start
                onLiveTraceUpdate?.invoke(
                    ToolCallTrace(traceId, toolName, category, argsSummary, msg, elapsed, "denied")
                )
                repository.logToolExecution(activeSessionId, toolName, category, argsSummary, msg, elapsed, false)
                return@runBlocking msg
            }
        }

        val (ok, rawResult) = runCatching { block() }.fold(
            onSuccess = { true to it },
            onFailure = { false to "Errore $toolName: ${it.message}" }
        )
        // Keep tool outputs crisp and cut at clean line/word boundaries so KV-cache RAM stays bounded
        val result = truncateToolOutput(rawResult, 1600)
        if (ok) {
            synchronized(turnLock) {
                turnCallSignatures.add(normSig)
                if (result.isNotBlank()) {
                    turnToolOutputs.add(toolName to result)
                }
            }
        }
        val elapsed = System.currentTimeMillis() - start
        onLiveTraceUpdate?.invoke(
            ToolCallTrace(
                id = traceId,
                toolName = toolName,
                category = category,
                argumentsSummary = argsSummary,
                resultSummary = truncateToolOutput(result, 500),
                durationMs = elapsed,
                status = if (ok) "success" else "error"
            )
        )
        repository.logToolExecution(activeSessionId, toolName, category, argsSummary, result, elapsed, ok)
        result
    }

    private fun truncateToolOutput(raw: String, maxChars: Int): String {
        val text = raw.trim()
        if (text.length <= maxChars) return text
        val slice = text.take(maxChars)
        val lastNewline = slice.lastIndexOf('\n')
        if (lastNewline >= (maxChars * 0.65).toInt()) {
            return slice.substring(0, lastNewline).trimEnd() + "\n..."
        }
        val lastSpace = slice.lastIndexOf(' ')
        if (lastSpace >= (maxChars * 0.75).toInt()) {
            return slice.substring(0, lastSpace).trimEnd() + "..."
        }
        return slice.trimEnd() + "..."
    }
}

class MemoryAndSkillsToolSet(
    private val repository: MobHarnRepository,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    private fun normalizeMemoryCategory(raw: String): String {
        return when (raw.trim().uppercase().replace('-', '_').replace(' ', '_')) {
            "USER_PROFILE", "PROFILE", "USER", "UTENTE", "PERSONAL" -> "USER_PROFILE"
            "PREFERENCE", "PREFERENCES", "PREF", "PREFERENZA" -> "PREFERENCE"
            "PROJECT", "PROJECTS", "PROGETTO" -> "PROJECT"
            "CODING_CONVENTION", "CODING", "CODE", "STYLE" -> "CODING_CONVENTION"
            else -> "FACT"
        }
    }

    private fun normalizeSlugOrKey(raw: String, fallbackPrefix: String): String {
        val cleaned = raw.trim()
            .removePrefix("/")
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return cleaned.ifBlank { "${fallbackPrefix}_${System.currentTimeMillis() % 10000}" }
    }

    @Tool(description = "Salva o aggiorna un fatto persistente in USER.md o MEMORY.md (categorie: USER_PROFILE, PREFERENCE, FACT, PROJECT, CODING_CONVENTION)")
    fun save_memory(
        @ToolParam(description = "Categoria: USER_PROFILE, PREFERENCE, FACT, PROJECT o CODING_CONVENTION") category: String,
        @ToolParam(description = "Chiave breve univoca in snake_case") keyName: String,
        @ToolParam(description = "Contenuto chiaro e conciso del ricordo") content: String,
        @ToolParam(description = "Importanza da 1 a 5") importance: Int = 3
    ): String {
        val cleanCategory = normalizeMemoryCategory(category)
        val cleanKey = normalizeSlugOrKey(keyName, "nota")
        val cleanContent = content.trim()
        val clampedImportance = importance.coerceIn(1, 5)
        return monitor.executeTracked(
            toolName = "save_memory",
            category = ToolGroup.MEMORY.id,
            argsSummary = "[$cleanCategory] $cleanKey = ${cleanContent.take(80)}"
        ) {
            if (cleanContent.isBlank()) {
                return@executeTracked "Errore save_memory: specifica un contenuto non vuoto da salvare."
            }
            repository.upsertMemoryAndSync(cleanCategory, cleanKey, cleanContent, clampedImportance)
            "Memoria salvata e sincronizzata su Markdown: [$cleanCategory] $cleanKey"
        }
    }

    @Tool(description = "Cerca nei fatti persistenti di USER.md e MEMORY.md per parola chiave")
    fun search_memory(
        @ToolParam(description = "Parola chiave o tema da cercare") query: String
    ): String = monitor.executeTracked(
        toolName = "search_memory",
        category = ToolGroup.MEMORY.id,
        argsSummary = "query=\"${query.trim()}\""
    ) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@executeTracked "Specifica una parola chiave non vuota per cercare nella memoria."
        }
        val results = repository.searchMemories(cleanQuery)
        if (results.isEmpty()) "Nessuna voce di memoria trovata per '$cleanQuery'."
        else results.take(12).joinToString("\n") { "- [${it.category}] ${it.keyName}: ${it.content}" }
    }

    @Tool(description = "Cerca nello storico completo di tutte le conversazioni passate tramite SQLite FTS")
    fun search_past_sessions(
        @ToolParam(description = "Parole chiave da cercare nelle vecchie chat") query: String
    ): String = monitor.executeTracked(
        toolName = "search_past_sessions",
        category = ToolGroup.MEMORY.id,
        argsSummary = "fts=\"${query.trim()}\""
    ) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@executeTracked "Specifica almeno una parola chiave per cercare nelle chat passate."
        }
        val hits = repository.searchSessionsHistory(cleanQuery, limit = 8)
        if (hits.isEmpty()) "Nessun messaggio passato trovato per '$cleanQuery'."
        else hits.joinToString("\n") { m ->
            "[Sessione #${m.sessionId} • ${m.role}]: ${m.content.take(220)}"
        }
    }

    @Tool(description = "Carica le istruzioni complete di una Skill procedurale per nome/slug")
    fun load_skill(
        @ToolParam(description = "Slug della skill (es. coding_workflow, daily_briefing)") slug: String
    ): String {
        val rawNoSlash = slug.trim().removePrefix("/")
        val cleanSlug = normalizeSlugOrKey(rawNoSlash, "")
        return monitor.executeTracked(
            toolName = "load_skill",
            category = ToolGroup.MEMORY.id,
            argsSummary = "slug=${cleanSlug.ifBlank { rawNoSlash }}"
        ) {
            if (rawNoSlash.isBlank()) {
                return@executeTracked "Specifica lo slug della skill da caricare (usa list_skills per vedere quelle disponibili)."
            }
            val skill = repository.loadSkill(cleanSlug) ?: repository.loadSkill(rawNoSlash)
                ?: return@executeTracked "Skill '$rawNoSlash' non trovata."
            "# Skill: ${skill.title} (/${skill.slug})\n${skill.instructionsMd}"
        }
    }

    @Tool(description = "Salva o aggiorna una Skill procedurale riutilizzabile nella libreria locale")
    fun save_skill(
        @ToolParam(description = "Slug breve univoco (es. kotlin_refactor)") slug: String,
        @ToolParam(description = "Titolo descrittivo") title: String,
        @ToolParam(description = "Breve descrizione di quando usarla") description: String,
        @ToolParam(description = "Istruzioni passo-passo in Markdown") instructionsMd: String
    ): String {
        val cleanSlug = normalizeSlugOrKey(slug, "skill")
        val cleanTitle = title.trim().ifBlank { cleanSlug }
        val cleanDesc = description.trim().ifBlank { "Procedura salvata" }
        val cleanInstructions = instructionsMd.trim()
        return monitor.executeTracked(
            toolName = "save_skill",
            category = ToolGroup.MEMORY.id,
            argsSummary = "slug=$cleanSlug, title=$cleanTitle"
        ) {
            if (cleanInstructions.isBlank()) {
                return@executeTracked "Errore save_skill: le istruzioni Markdown della skill non possono essere vuote."
            }
            if (cleanSlug.length > 64 || cleanTitle.length > 80 || cleanDesc.length > 160 || cleanInstructions.length > 600)
                return@executeTracked "Proposta troppo lunga. Riducila a una regola breve, senza dati personali."
            val proposed = com.example.data.db.AgentSkillEntity(slug = cleanSlug, title = cleanTitle, description = cleanDesc, instructionsMd = cleanInstructions)
            if (monitor.onSkillProposal?.invoke(proposed) == true) "Bozza in attesa di revisione; non ancora salvata."
            else "Nessuna scrittura. Per una correzione indica: Correzione: tema | regola."
        }
    }

    @Tool(description = "Elenca tutte le Skill procedurali salvate nella libreria locale")
    fun list_skills(): String = monitor.executeTracked(
        toolName = "list_skills",
        category = ToolGroup.MEMORY.id,
        argsSummary = "list_skills"
    ) {
        val list = repository.getEnabledSkills()
        if (list.isEmpty()) "Nessuna skill salvata."
        else list.joinToString("\n") { "- /${it.slug} (${it.title}, usata ${it.usageCount}x): ${it.description}" }
    }

    @Tool(description = "Elimina un ricordo obsoleto o errato dalla memoria persistente cercandolo per chiave o testo")
    fun delete_memory(
        @ToolParam(description = "Chiave o parola chiave del ricordo da rimuovere") query: String
    ): String = monitor.executeTracked(
        toolName = "delete_memory",
        category = ToolGroup.MEMORY.id,
        argsSummary = "delete \"${query.trim()}\""
    ) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@executeTracked "Specifica la chiave o il testo del ricordo da rimuovere."
        }
        val hits = repository.searchMemories(cleanQuery)
        val exact = hits.firstOrNull { it.keyName.equals(cleanQuery, ignoreCase = true) } ?: hits.firstOrNull()
            ?: return@executeTracked "Nessun ricordo trovato per '$cleanQuery'."
        repository.deleteMemoryAndSync(exact.id)
        "Ricordo rimosso: [${exact.category}] ${exact.keyName}"
    }
}

class CodingWorkspaceToolSet(
    private val repository: MobHarnRepository,
    private val sandbox: CodeSandboxRunner,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Elenca tutti i file nel progetto Coding Workspace attivo")
    fun list_workspace_files(): String = monitor.executeTracked(
        toolName = "list_workspace_files",
        category = ToolGroup.CODING.id,
        argsSummary = "project=${monitor.activeProjectName}"
    ) {
        val files = repository.workspace.listFiles(monitor.activeProjectName)
        if (files.isEmpty()) "Progetto '${monitor.activeProjectName}' vuoto."
        else files.joinToString("\n") { "${it.relativePath} (${it.sizeBytes} B)" }
    }

    @Tool(description = "Legge un intervallo di righe di un file del Coding Workspace con numeri di riga")
    fun read_file_lines(
        @ToolParam(description = "Percorso relativo del file (es. app.js o src/Main.kt)") relativePath: String,
        @ToolParam(description = "Prima riga da leggere (1-based)") startLine: Int = 1,
        @ToolParam(description = "Ultima riga da leggere") endLine: Int = 150
    ): String = monitor.executeTracked(
        toolName = "read_file_lines",
        category = ToolGroup.CODING.id,
        argsSummary = "$relativePath [$startLine..$endLine]"
    ) {
        repository.workspace.readFileLines(monitor.activeProjectName, relativePath, startLine, endLine)
            .getOrElse { "Errore lettura: ${it.message}" }
    }

    @Tool(description = "Crea o sovrascrive un file nel Coding Workspace salvando automaticamente uno snapshot di rollback")
    fun write_workspace_file(
        @ToolParam(description = "Percorso relativo del file") relativePath: String,
        @ToolParam(description = "Contenuto completo del file") content: String,
        @ToolParam(description = "Breve riassunto della modifica") summary: String = "Aggiornato da Gemma 4"
    ): String = monitor.executeTracked(
        toolName = "write_workspace_file",
        category = ToolGroup.CODING.id,
        argsSummary = "$relativePath (${content.length} chars)"
    ) {
        val project = monitor.activeProjectName
        val prev = repository.workspace.writeFile(project, relativePath, content).getOrThrow()
        repository.recordSnapshot(project, relativePath, prev, content, summary)
        "File '$relativePath' scritto nel progetto '$project' (${content.lines().size} righe). Snapshot salvato."
    }

    @Tool(description = "Applica una patch chirurgica sostituendo un blocco di testo esatto in un file del workspace")
    fun patch_workspace_file(
        @ToolParam(description = "Percorso relativo del file") relativePath: String,
        @ToolParam(description = "Blocco di testo esatto da sostituire") oldText: String,
        @ToolParam(description = "Nuovo testo sostitutivo") newText: String
    ): String = monitor.executeTracked(
        toolName = "patch_workspace_file",
        category = ToolGroup.CODING.id,
        argsSummary = "patch $relativePath"
    ) {
        val project = monitor.activeProjectName
        val (prev, updated) = repository.workspace.patchFile(project, relativePath, oldText, newText).getOrThrow()
        repository.recordSnapshot(project, relativePath, prev, updated, "Patch su $relativePath")
        "Patch applicata con successo a '$relativePath' (${updated.lines().size} righe)."
    }

    @Tool(description = "Cerca codice tramite testo o regex in tutti i file del progetto attivo")
    fun search_code(
        @ToolParam(description = "Pattern regex o parola chiave") pattern: String
    ): String = monitor.executeTracked(
        toolName = "search_code",
        category = ToolGroup.CODING.id,
        argsSummary = "grep /$pattern/"
    ) {
        val matches = repository.workspace.searchCode(monitor.activeProjectName, pattern)
        if (matches.isEmpty()) "Nessun match per '$pattern' in ${monitor.activeProjectName}."
        else matches.joinToString("\n") { "${it.relativePath}:${it.lineNumber}: ${it.lineContent}" }
    }

    @Tool(description = "Esegue codice JavaScript ES6+ nella sandbox offline e restituisce console.log, return value o eventuali errori")
    fun run_js_sandbox(
        @ToolParam(description = "Codice JavaScript ES6+ da eseguire e testare") code: String
    ): String = monitor.executeTracked(
        toolName = "run_js_sandbox",
        category = ToolGroup.CODING.id,
        argsSummary = "${code.lines().size} righe JS"
    ) {
        val res = sandbox.runJavaScript(code)
        val status = if (res.success) "OK" else "ERRORE"
        "[$status • ${res.durationMs}ms]\n${res.output}"
    }

    @Tool(description = "Esegue uno script SQLite (CREATE/INSERT/SELECT) nella sandbox SQL in-memory e restituisce la tabella dei risultati")
    fun run_sql_sandbox(
        @ToolParam(description = "Script SQL da eseguire") sqlScript: String
    ): String = monitor.executeTracked(
        toolName = "run_sql_sandbox",
        category = ToolGroup.CODING.id,
        argsSummary = "${sqlScript.take(80)}"
    ) {
        val res = sandbox.runSql(sqlScript)
        val status = if (res.success) "OK" else "ERRORE"
        "[$status • ${res.durationMs}ms]\n${res.output}"
    }
}

class AndroidActionsToolSet(
    private val system: AndroidSystemActions,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Imposta una sveglia sul telefono Pixel")
    fun set_alarm(
        @ToolParam(description = "Ora 0-23") hour: Int,
        @ToolParam(description = "Minuti 0-59") minute: Int,
        @ToolParam(description = "Etichetta della sveglia") message: String = "MobHarn"
    ): String = monitor.executeTracked(
        toolName = "set_alarm",
        category = ToolGroup.ANDROID_ACTIONS.id,
        exactCommand = if (hour in 0..23 && minute in 0..59 && message.length <= 48 && message.none { it.isISOControl() }) listOf(hour.toString(), minute.toString(), message) else null,
        argsSummary = "%02d:%02d (%s)".format(hour, minute, message),
        isExternalOrMutating = true,
        approvalDescription = "Impostare una sveglia alle %02d:%02d con etichetta '$message'?".format(hour, minute)
    ) {
        system.setAlarm(hour, minute, message)
    }

    @Tool(description = "Apre la creazione di un evento nel Calendario Android")
    fun create_calendar_event(
        @ToolParam(description = "Titolo dell'evento") title: String,
        @ToolParam(description = "Descrizione o note") description: String = "",
        @ToolParam(description = "Tra quanti minuti inizia l'evento") startInMinutes: Int = 60,
        @ToolParam(description = "Durata in minuti") durationMinutes: Int = 60
    ): String = monitor.executeTracked(
        toolName = "create_calendar_event",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = "$title (+${startInMinutes}m, ${durationMinutes}m)",
        isExternalOrMutating = true,
        approvalDescription = "Creare l'evento calendario '$title'?"
    ) {
        system.createCalendarEvent(title, description, startInMinutes.toLong(), durationMinutes.toLong())
    }

    @Tool(description = "Apre Google Maps cercando un luogo o indirizzo")
    fun open_maps(
        @ToolParam(description = "Luogo, indirizzo o punto di interesse") query: String
    ): String = monitor.executeTracked(
        toolName = "open_maps",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = query,
        isExternalOrMutating = false
    ) {
        system.openMaps(query)
    }

    @Tool(description = "Avvia subito un'applicazione installata sul telefono Pixel cercandola per nome (es. YouTube, WhatsApp, Chrome, Fotocamera, Spotify, Impostazioni)")
    fun launch_app(
        @ToolParam(description = "Nome dell'app da aprire (es. YouTube, WhatsApp, Fotocamera, Spotify, Gmail)") appName: String
    ): String = monitor.executeTracked(
        toolName = "launch_app",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = appName,
        isExternalOrMutating = false
    ) {
        system.launchApp(appName)
    }

    @Tool(description = "Apre un URL nel browser predefinito")
    fun open_url(
        @ToolParam(description = "URL completo https://...") url: String
    ): String = monitor.executeTracked(
        toolName = "open_url",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = url,
        isExternalOrMutating = false
    ) {
        system.openUrl(url)
    }

    @Tool(description = "Avvia un timer sul Pixel")
    fun set_timer(
        @ToolParam(description = "Durata in secondi") seconds: Int,
        @ToolParam(description = "Etichetta timer") message: String = "Timer MobHarn"
    ): String = monitor.executeTracked(
        toolName = "set_timer",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = "${seconds}s ($message)",
        isExternalOrMutating = false
    ) {
        system.setTimer(seconds, message)
    }

    @Tool(description = "Prepara una bozza email nell'app email del Pixel")
    fun compose_email(
        @ToolParam(description = "Indirizzo destinatario") to: String,
        @ToolParam(description = "Oggetto email") subject: String,
        @ToolParam(description = "Corpo del messaggio") body: String
    ): String = monitor.executeTracked(
        toolName = "compose_email",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = "to=$to, subj=${subject.take(40)}",
        isExternalOrMutating = true,
        approvalDescription = "Preparare bozza email per '$to' con oggetto '$subject'?"
    ) {
        system.composeEmail(to, subject, body)
    }

    @Tool(description = "Prepara un messaggio SMS verso un numero di telefono")
    fun compose_sms(
        @ToolParam(description = "Numero di telefono destinatario") phoneNumber: String,
        @ToolParam(description = "Testo del messaggio") message: String
    ): String = monitor.executeTracked(
        toolName = "compose_sms",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = "sms $phoneNumber",
        isExternalOrMutating = true,
        approvalDescription = "Aprire bozza SMS verso $phoneNumber?"
    ) {
        system.composeSms(phoneNumber, message)
    }

    @Tool(description = "Apre il tastierino telefonico con un numero pronto per la chiamata")
    fun dial_number(
        @ToolParam(description = "Numero di telefono") phoneNumber: String
    ): String = monitor.executeTracked(
        toolName = "dial_number",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = "dial $phoneNumber",
        isExternalOrMutating = true,
        approvalDescription = "Aprire il tastierino con il numero $phoneNumber?"
    ) {
        system.dialNumber(phoneNumber)
    }

    @Tool(description = "Apre il foglio di condivisione Android per condividere testo in qualsiasi app")
    fun share_text(
        @ToolParam(description = "Testo da condividere") text: String
    ): String = monitor.executeTracked(
        toolName = "share_text",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = text.take(50),
        isExternalOrMutating = true,
        approvalDescription = "Aprire la condivisione Android per questo testo?"
    ) {
        system.shareText(text)
    }

    @Tool(description = "Apre una sezione delle Impostazioni Android (wifi, bluetooth, battery, accessibility, display, general)")
    fun open_system_settings(
        @ToolParam(description = "Sezione: wifi, bluetooth, battery, accessibility, display o general") section: String
    ): String = monitor.executeTracked(
        toolName = "open_system_settings",
        category = ToolGroup.ANDROID_ACTIONS.id,
        argsSummary = "settings:$section",
        isExternalOrMutating = false
    ) {
        system.openSystemSettings(section)
    }
}

class WebAndCalcToolSet(
    private val system: AndroidSystemActions,
    private val sandbox: CodeSandboxRunner,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Esegue una ricerca web leggera e restituisce i risultati principali")
    fun web_search(
        @ToolParam(description = "Query di ricerca web") query: String
    ): String = monitor.executeTracked(
        toolName = "web_search",
        category = ToolGroup.WEB_CALC.id,
        argsSummary = query
    ) {
        system.webSearch(query)
    }

    @Tool(description = "Scarica ed estrae il testo pulito di una pagina web da un URL")
    fun fetch_webpage(
        @ToolParam(description = "URL https://... della pagina da leggere") url: String
    ): String = monitor.executeTracked(
        toolName = "fetch_webpage",
        category = ToolGroup.WEB_CALC.id,
        argsSummary = url
    ) {
        system.fetchWebpage(url)
    }

    @Tool(description = "Calcola espressioni matematiche precise (supporta sqrt, sin, cos, log, ln, pow, pi, e)")
    fun calculate_expression(
        @ToolParam(description = "Espressione matematica es. (24*365) + sqrt(144)") expression: String
    ): String = monitor.executeTracked(
        toolName = "calculate_expression",
        category = ToolGroup.WEB_CALC.id,
        argsSummary = expression
    ) {
        sandbox.evaluateMath(expression).output
    }

    @Tool(description = "Valida/formatta JSON oppure testa una Regex su un testo")
    fun validate_json_regex(
        @ToolParam(description = "'json' oppure 'regex'") mode: String,
        @ToolParam(description = "Testo o stringa JSON da analizzare") input: String,
        @ToolParam(description = "Pattern regex (solo se mode='regex')") pattern: String = ""
    ): String = monitor.executeTracked(
        toolName = "validate_json_regex",
        category = ToolGroup.WEB_CALC.id,
        argsSummary = "mode=$mode"
    ) {
        sandbox.validateJsonOrRegex(mode, input, pattern).output
    }
}

class DeviceTelemetryToolSet(
    private val system: AndroidSystemActions,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Legge telemetria reale del Pixel: batteria, temperatura, RAM libera, storage, CPU e stato rete")
    fun get_device_telemetry(): String = monitor.executeTracked(
        toolName = "get_device_telemetry",
        category = ToolGroup.TELEMETRY.id,
        argsSummary = "system_telemetry"
    ) {
        system.getTelemetryReport()
    }

    @Tool(description = "Legge il testo attualmente presente negli appunti del telefono")
    fun read_clipboard(): String = monitor.executeTracked(
        toolName = "read_clipboard",
        category = ToolGroup.TELEMETRY.id,
        argsSummary = "clipboard"
    ) {
        system.readClipboardText()
    }

    @Tool(description = "Cattura un'istantanea dai sensori fisici (luce ambientale, accelerometro, barometro, magnetometro)")
    fun get_sensor_snapshot(): String = monitor.executeTracked(
        toolName = "get_sensor_snapshot",
        category = ToolGroup.TELEMETRY.id,
        argsSummary = "sensors"
    ) {
        system.getSensorSnapshot()
    }

    @Tool(description = "Legge la posizione approssimativa del dispositivo se il permesso è stato concesso")
    fun get_approx_location(): String = monitor.executeTracked(
        toolName = "get_approx_location",
        category = ToolGroup.TELEMETRY.id,
        argsSummary = "coarse_location"
    ) {
        system.getApproxLocation()
    }
}

class AutomationToolSet(
    private val repository: MobHarnRepository,
    private val scheduler: AutomationScheduler,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Pianifica un'automazione in background tramite WorkManager (actionType: BRIEFING, TELEMETRY_CHECK, WEB_MONITOR, MEMORY_DIGEST, WORKSPACE_AUDIT)")
    fun schedule_automation(
        @ToolParam(description = "Nome dell'automazione") name: String,
        @ToolParam(description = "Prompt o istruzione dell'automazione") prompt: String,
        @ToolParam(description = "BRIEFING, TELEMETRY_CHECK, WEB_MONITOR, MEMORY_DIGEST o WORKSPACE_AUDIT") actionType: String = "BRIEFING",
        @ToolParam(description = "Intervallo in minuti (minimo 15 se periodica)") intervalMinutes: Int = 60,
        @ToolParam(description = "true per ricorrente, false per una tantum") isPeriodic: Boolean = true
    ): String = monitor.executeTracked(
        toolName = "schedule_automation",
        category = ToolGroup.AUTOMATIONS.id,
        argsSummary = "$name (${actionType}, ${intervalMinutes}m)",
        isExternalOrMutating = true,
        approvalDescription = "Pianificare l'automazione '$name' ogni $intervalMinutes min?"
    ) {
        val tag = "mobharn_auto_${System.currentTimeMillis()}"
        val entity = AutomationTaskEntity(
            name = name,
            prompt = prompt,
            actionType = actionType.uppercase(),
            intervalMinutes = intervalMinutes.toLong().coerceAtLeast(if (isPeriodic) 15L else 1L),
            isPeriodic = isPeriodic,
            enabled = true,
            workTag = tag
        )
        val id = repository.upsertAutomation(entity)
        scheduler.schedule(entity.copy(id = id))
        "Automazione '$name' pianificata in background (ID #$id)."
    }
}

class MobHarnToolkitRegistry(
    private val repository: MobHarnRepository,
    private val system: AndroidSystemActions,
    private val sandbox: CodeSandboxRunner,
    private val scheduler: AutomationScheduler,
    val monitor: ToolExecutionMonitor
) {
    val memoryTools = MemoryAndSkillsToolSet(repository, monitor)
    val codingTools = CodingWorkspaceToolSet(repository, sandbox, monitor)
    val androidTools = AndroidActionsToolSet(system, monitor)
    val webCalcTools = WebAndCalcToolSet(system, sandbox, monitor)
    val telemetryTools = DeviceTelemetryToolSet(system, monitor)
    val automationTools = AutomationToolSet(repository, scheduler, monitor)
    val dataTools = DataToolSet(repository, sandbox, monitor)
    val selfEvolveTools = SelfEvolveToolSet(repository, sandbox, monitor)
    val phoneUseTools = PhoneUseToolSet(monitor)
    /** Offer phone controls only while the user-enabled accessibility service is connected. */
    fun selectRoutedToolGroups(userPrompt: String, enabledGroups: Set<String>): Set<String> {
        return routeToolGroupsForPrompt(userPrompt, enabledGroups, MobHarnAccessibilityService.isConnected.value)
    }

    fun buildEnabledToolProviders(enabledGroups: Set<String>): List<ToolProvider> {
        val providers = mutableListOf<ToolProvider>()
        if (ToolGroup.WEB_CALC.id in enabledGroups) providers.add(tool(webCalcTools))
        if (ToolGroup.MEMORY.id in enabledGroups) providers.add(tool(memoryTools))
        if (ToolGroup.CODING.id in enabledGroups) providers.add(tool(codingTools))
        if (ToolGroup.ANDROID_ACTIONS.id in enabledGroups) providers.add(tool(androidTools))
        if (ToolGroup.TELEMETRY.id in enabledGroups) providers.add(tool(telemetryTools))
        if (ToolGroup.AUTOMATIONS.id in enabledGroups) providers.add(tool(automationTools))
        if ("data_tools" in enabledGroups || ToolGroup.SELF_EVOLVE.id in enabledGroups) providers.add(tool(dataTools))
        if (ToolGroup.SELF_EVOLVE.id in enabledGroups) providers.add(tool(selfEvolveTools))
        if (ToolGroup.PHONE_USE.id in enabledGroups) providers.add(tool(phoneUseTools))
        return providers
    }

    /**
     * Direct tracked dispatcher used by LiteRtRuntimeManager's Tool-Call Recovery layer whenever
     * LiteRT-LM's strict C++ ANTLR parser fails on a slightly malformed call (e.g. `"` instead of `<|"|>`).
     */
    fun invokeToolByName(rawName: String, args: Map<String, Any?>, routedGroups: Set<String>): String? {
        fun gated(group: String, call: () -> String): String =
            if (group in routedGroups || (group == "data_tools" && "self_evolve" in routedGroups)) call()
            else "Strumento non attivo in questo turno. Non aggirare il gruppo o i permessi."

        val normalized = ToolCallRecoveryParser.normalizeToolName(rawName)
        if (normalized.isBlank()) return null

        fun lookup(key: String): Any? {
            val raw = args[key] ?: args.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            return if (raw == null || raw == JSONObject.NULL) null else raw
        }

        fun str(vararg keys: String, default: String = ""): String {
            for (k in keys) {
                val direct = lookup(k)
                if (direct != null) {
                    val s = direct.toString().trim()
                    if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) return s
                }
            }
            if (args.size == 1 && keys.isNotEmpty()) {
                val rawSingle = args.values.firstOrNull()
                if (rawSingle != null && rawSingle != JSONObject.NULL) {
                    val single = rawSingle.toString().trim()
                    if (single.isNotEmpty() && !single.equals("null", ignoreCase = true)) return single
                }
            }
            return default
        }

        fun intArg(vararg keys: String, default: Int): Int {
            for (k in keys) {
                when (val raw = lookup(k)) {
                    is Number -> return raw.toInt()
                    is String -> raw.trim().toIntOrNull()?.let { return it }
                }
            }
            return default
        }

        fun doubleArg(vararg keys: String, default: Double): Double {
            for (k in keys) {
                when (val raw = lookup(k)) {
                    is Number -> return raw.toDouble()
                    is String -> raw.trim().replace(',', '.').toDoubleOrNull()?.let { return it }
                }
            }
            return default
        }

        fun boolArg(vararg keys: String, default: Boolean): Boolean {
            for (k in keys) {
                when (val raw = lookup(k)) {
                    is Boolean -> return raw
                    is String -> when (raw.trim().lowercase()) {
                        "true", "1", "yes", "si", "sì" -> return true
                        "false", "0", "no" -> return false
                    }
                }
            }
            return default
        }

        return when (normalized) {
            // Web & Calc
            "web_search", "search_web", "google_search", "search", "internet_search", "wiki_search", "wikipedia" -> gated("web_calc") {
                val q = str("query", "q", "search_query", "text", "topic")
                if (q.isBlank()) "Errore web_search: parametro 'query' mancante o vuoto."
                else webCalcTools.web_search(q)
            }
            "fetch_webpage", "fetch_url", "read_webpage", "browse_url", "get_webpage" -> gated("web_calc") {
                val url = str("url", "link", "href")
                if (url.isBlank()) "Errore fetch_webpage: parametro 'url' mancante o vuoto."
                else webCalcTools.fetch_webpage(url)
            }
            "calculate_expression", "calculate", "calc", "calculator", "math", "eval_math" -> gated("web_calc") {
                val expr = str("expression", "expr", "formula", "input")
                if (expr.isBlank()) "Errore calculate_expression: parametro 'expression' mancante o vuoto."
                else webCalcTools.calculate_expression(expr)
            }
            "validate_json_regex", "validate_json_or_regex" -> gated("web_calc") {
                webCalcTools.validate_json_regex(
                    mode = str("mode", default = "json"),
                    input = str("input", "text", "data"),
                    pattern = str("pattern", "regex")
                )
            }
            // Memory & Skills
            "save_memory", "remember_fact", "remember", "remember_this", "memorize", "add_memory" -> gated("memory") {
                val content = str("content", "value", "fact", "text")
                if (content.isBlank()) "Errore save_memory: parametro 'content' mancante o vuoto."
                else memoryTools.save_memory(
                    category = str("category", "cat", default = "FACT"),
                    keyName = str("keyName", "key_name", "key", default = "nota_${System.currentTimeMillis() % 10000}"),
                    content = content,
                    importance = intArg("importance", "priority", default = 3)
                )
            }
            "search_memory", "recall_memory", "get_memory", "get_memories", "find_memory" -> gated("memory") {
                memoryTools.search_memory(str("query", "q", "keyword", "key"))
            }
            "delete_memory", "forget_memory" -> gated("memory") {
                memoryTools.delete_memory(str("query", "keyName", "key", "q"))
            }
            "search_past_sessions", "search_history" -> gated("memory") {
                memoryTools.search_past_sessions(str("query", "q", "keyword"))
            }
            "list_skills" -> gated("memory") {
                memoryTools.list_skills()
            }
            "load_skill", "use_skill", "get_skill" -> gated("memory") {
                val slug = str("slug", "name", "skill")
                if (slug.isBlank()) "Errore load_skill: parametro 'slug' mancante."
                else memoryTools.load_skill(slug)
            }
            "save_skill", "create_skill", "learn_skill" -> gated("memory") {
                memoryTools.save_skill(
                    slug = str("slug", "name", default = "skill_${System.currentTimeMillis() % 10000}"),
                    title = str("title", "name", default = "Skill appresa"),
                    description = str("description", "desc", default = "Procedura appresa in chat"),
                    instructionsMd = str("instructionsMd", "instructions_md", "instructions", "content")
                )
            }
            // Android System Actions
            "launch_app", "open_app", "start_app" -> gated("android_actions") {
                val app = str("appName", "app_name", "app", "name", "package")
                if (app.isBlank()) "Errore launch_app: specifica il nome dell'app da aprire."
                else androidTools.launch_app(app)
            }
            "open_url", "open_link", "open_browser" -> gated("android_actions") {
                val url = str("url", "link")
                if (url.isBlank()) "Errore open_url: specifica l'URL da aprire."
                else androidTools.open_url(url)
            }
            "open_maps", "maps" -> gated("android_actions") {
                val query = str("query", "location", "place", "address")
                if (query.isBlank()) "Errore open_maps: specifica un luogo o indirizzo."
                else androidTools.open_maps(query)
            }
            "set_alarm" -> gated("android_actions") {
                androidTools.set_alarm(
                    hour = intArg("hour", "hours", "h", default = 8),
                    minute = intArg("minute", "minutes", "m", default = 0),
                    message = str("message", "label", "title", default = "MobHarn")
                )
            }
            "set_timer", "start_timer" -> gated("android_actions") {
                androidTools.set_timer(
                    seconds = intArg("seconds", "duration", "sec", default = 60),
                    message = str("message", "label", default = "Timer MobHarn")
                )
            }
            "create_calendar_event" -> gated("android_actions") {
                androidTools.create_calendar_event(
                    title = str("title", "name", default = "Evento"),
                    description = str("description", "notes"),
                    startInMinutes = intArg("startInMinutes", "start_in_minutes", default = 60),
                    durationMinutes = intArg("durationMinutes", "duration_minutes", default = 60)
                )
            }
            "compose_email", "draft_email" -> gated("android_actions") {
                androidTools.compose_email(
                    to = str("to", "recipient", "email"),
                    subject = str("subject", "title"),
                    body = str("body", "text", "message")
                )
            }
            "compose_sms", "draft_sms" -> gated("android_actions") {
                androidTools.compose_sms(
                    phoneNumber = str("phoneNumber", "phone_number", "to", "number"),
                    message = str("message", "body", "text")
                )
            }
            "dial_number", "call_number" -> gated("android_actions") {
                androidTools.dial_number(str("phoneNumber", "phone_number", "number"))
            }
            "share_text" -> gated("android_actions") {
                androidTools.share_text(str("text", "content", "message"))
            }
            "open_system_settings", "open_settings" -> gated("android_actions") {
                androidTools.open_system_settings(str("section", "setting", default = "general"))
            }
            // Coding Workspace & Sandbox
            "list_workspace_files", "list_files" -> gated("coding") {
                codingTools.list_workspace_files()
            }
            "read_file_lines", "read_file", "read_workspace_file" -> gated("coding") {
                codingTools.read_file_lines(
                    relativePath = str("relativePath", "relative_path", "path", "file"),
                    startLine = intArg("startLine", "start_line", default = 1),
                    endLine = intArg("endLine", "end_line", default = 150)
                )
            }
            "write_workspace_file", "write_file" -> gated("coding") {
                codingTools.write_workspace_file(
                    relativePath = str("relativePath", "relative_path", "path", "file"),
                    content = str("content", "code", "text"),
                    summary = str("summary", default = "Aggiornato da Gemma 4")
                )
            }
            "patch_workspace_file", "patch_file" -> gated("coding") {
                codingTools.patch_workspace_file(
                    relativePath = str("relativePath", "relative_path", "path", "file"),
                    oldText = str("oldText", "old_text", "search"),
                    newText = str("newText", "new_text", "replace")
                )
            }
            "search_code", "grep_workspace" -> gated("coding") {
                codingTools.search_code(str("pattern", "query", "q"))
            }
            "run_js_sandbox", "run_javascript", "run_js", "execute_js" -> gated("coding") {
                codingTools.run_js_sandbox(str("code", "script", "js"))
            }
            "run_sql_sandbox", "run_sql", "execute_sql" -> gated("coding") {
                codingTools.run_sql_sandbox(str("sqlScript", "sql_script", "sql", "query"))
            }
            // Telemetry
            "get_device_telemetry", "device_telemetry", "telemetry", "get_telemetry", "battery_status" -> gated("telemetry") {
                telemetryTools.get_device_telemetry()
            }
            "read_clipboard", "get_clipboard" -> gated("telemetry") {
                telemetryTools.read_clipboard()
            }
            "get_sensor_snapshot", "get_sensors" -> gated("telemetry") {
                telemetryTools.get_sensor_snapshot()
            }
            "get_approx_location", "get_location" -> gated("telemetry") {
                telemetryTools.get_approx_location()
            }
            // Automations
            "schedule_automation" -> gated("automations") {
                automationTools.schedule_automation(
                    name = str("name", "title", default = "Automazione"),
                    prompt = str("prompt", "instruction", "query"),
                    actionType = str("actionType", "action_type", default = "BRIEFING"),
                    intervalMinutes = intArg("intervalMinutes", "interval_minutes", default = 60),
                    isPeriodic = boolArg("isPeriodic", "is_periodic", default = true)
                )
            }
            // Self-Evolve
            "create_custom_tool" -> gated("data_tools") {
                dataTools.create_custom_tool(
                    name = str("name"),
                    description = str("description"),
                    parametersDoc = str("parametersDoc", "parameters_doc", default = "inputJson"),
                    jsCode = str("jsCode", "js_code", "code"),
                    exampleInput = lookup("exampleInput")?.toString().orEmpty(),
                    expectedJson = lookup("expectedJson")?.toString().orEmpty()
                )
            }
            "list_custom_tools" -> gated("data_tools") {
                dataTools.list_custom_tools()
            }
            "run_custom_tool" -> gated("data_tools") {
                dataTools.run_custom_tool(
                    name = str("name"),
                    inputJson = str("inputJson", "input_json", "input", default = "{}")
                )
            }
            "delete_custom_tool" -> gated("self_evolve") {
                selfEvolveTools.delete_custom_tool(str("name"))
            }
            "update_soul_md" -> gated("self_evolve") {
                selfEvolveTools.update_soul_md(
                    mode = str("mode", default = "append"),
                    content = str("content", "text"),
                    rationale = str("rationale", default = "Miglioramento harness")
                )
            }
            "tune_harness_settings", "tune_harness_params" -> gated("self_evolve") {
                selfEvolveTools.tune_harness_settings(
                    temperature = doubleArg("temperature", default = -1.0),
                    topP = doubleArg("topP", "top_p", default = -1.0),
                    maxNumTokens = intArg("maxNumTokens", "max_num_tokens", default = -1),
                    activeProject = str("activeProject", "active_project"),
                    enableToolGroup = str("enableToolGroup", "enable_tool_group")
                )
            }
            // Phone-Use
            "read_screen_ui", "read_screen" -> gated("phone_use") {
                phoneUseTools.read_screen_ui(intArg("maxNodes", "max_nodes", default = 80))
            }
            "tap_screen_text", "tap_element" -> gated("phone_use") {
                phoneUseTools.tap_screen_text(str("targetText", "target_text", "text", "label"))
            }
            "tap_screen_coords", "tap_coordinates" -> gated("phone_use") {
                phoneUseTools.tap_screen_coords(
                    x = intArg("x", default = 0),
                    y = intArg("y", default = 0)
                )
            }
            "type_screen_text", "type_text" -> gated("phone_use") {
                phoneUseTools.type_screen_text(
                    fieldHint = str("fieldHint", "field_hint", "hint"),
                    text = str("text", "value")
                )
            }
            "scroll_screen" -> gated("phone_use") {
                phoneUseTools.scroll_screen(str("direction", default = "down"))
            }
            "phone_global_action", "system_navigate" -> gated("phone_use") {
                phoneUseTools.phone_global_action(str("action", "command", default = "home"))
            }
            else -> "Strumento non disponibile. Riusa un tool attivo; per dati prova un custom tool locale. Per nuove capacità Android chiedi all’utente, senza eseguire nulla."
        }
    }
}

fun encodeToolTracesToJson(traces: List<ToolCallTrace>): String {
    val arr = JSONArray()
    traces.forEach { t ->
        arr.put(
            JSONObject().apply {
                put("id", t.id)
                put("toolName", t.toolName)
                put("category", t.category)
                put("argumentsSummary", t.argumentsSummary)
                put("resultSummary", t.resultSummary)
                put("durationMs", t.durationMs)
                put("status", t.status)
            }
        )
    }
    return arr.toString()
}

fun decodeToolTracesFromJson(json: String): List<ToolCallTrace> {
    if (json.isBlank() || json == "[]") return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ToolCallTrace(
                id = o.optString("id"),
                toolName = o.optString("toolName"),
                category = o.optString("category"),
                argumentsSummary = o.optString("argumentsSummary"),
                resultSummary = o.optString("resultSummary"),
                durationMs = o.optLong("durationMs"),
                status = o.optString("status", "success")
            )
        }
    }.getOrDefault(emptyList())
}

fun routeToolGroupsForPrompt(userPrompt: String, enabledGroups: Set<String>, accessibilityConnected: Boolean): Set<String> {
    val p = userPrompt.lowercase().trim()
    if (p.isBlank()) return emptySet()

    fun String.containsAny(vararg needles: String): Boolean = needles.any { this.contains(it) }

    val selected = linkedSetOf<String>()
    fun addIfEnabled(group: ToolGroup) {
        if (group.id in enabledGroups) selected.add(group.id)
    }

    // Pure date/time questions are already answered by the live timestamp in the system prompt
    val isPureDateTimeQuestion = p.containsAny(
        "che giorno è", "che giorno e'", "che giorno e ", "che data è", "che data e'",
        "data di oggi", "giorno della settimana", "che ore sono", "che ora è", "che ora e'",
        "che anno è", "che mese è", "oggi che giorno"
    ) && !p.containsAny("cerca", "internet", "web", "notizie", "meteo", "sveglia", "timer", "calendario", "evento")

    // 1. Web Search / Live factual lookup / Math / URLs
    if (!isPureDateTimeQuestion && p.containsAny(
            "cerca", "ricerca", "internet", "web", "google", "online", "notizie", "news",
            "meteo", "previsioni", "ultime notizie", "notizie di oggi", "meteo oggi",
            "prezzo", "quanto costa", "http://", "https://", "www.", "sito", "link", "pagina",
            "calcola", "quanto fa", "radice", "percentuale", "json", "regex", "wikipedia",
            "popolazione", "abitanti", "quanti abitanti", "dove si trova", "capitale",
            "classifica", "risultato", "cambio", "valuta", "bitcoin", "borsa", "chi ha vinto",
            "chi è il", "chi e' il", "chi è la", "chi e' la"
        )
    ) {
        addIfEnabled(ToolGroup.WEB_CALC)
    }

    // 2. Coding Workspace & Sandbox
    if (p.containsAny(
            "codice", "code", "file", "workspace", "progetto", "studio", "script", "javascript",
            "js", "sql", "sqlite", "html", "css", "kotlin", "python", "patch", "bug",
            "funzione", "sandbox", "esegui", "compila", "testa", "index.html", "app.js"
        )
    ) {
        addIfEnabled(ToolGroup.CODING)
    }

    // 4. Android System Actions
    if (p.containsAny(
            "sveglia", "allarme", "timer", "calendario", "evento", "appuntamento", "mappa",
            "maps", "indicazioni", "apri ", "aprimi", "avvia ", "lancia ", "applicazione",
            "youtube", "whatsapp", "spotify", "chrome", "instagram", "telegram", "fotocamera",
            "camera", "galleria", "calcolatrice", "orologio", "gmail", "contatti",
            "email", "mail", "sms", "chiama ", "condividi", "impostazioni", "wifi", "bluetooth"
        )
    ) {
        addIfEnabled(ToolGroup.ANDROID_ACTIONS)
    }

    // 5. Device Telemetry & Sensors
    if (p.containsAny(
            "batteria", "ram", "memoria libera", "storage", "spazio", "temperatura", "cpu",
            "telemetria", "appunti", "clipboard", "sensore", "sensori", "barometro",
            "posizione", "dove sono", "gps"
        )
    ) {
        addIfEnabled(ToolGroup.TELEMETRY)
    }

    // 6. Self-Evolving Harness & Custom Tools
    if (p.containsAny(
            "harness", "custom tool", "nuovo tool", "crea tool", "crea uno strumento",
            "soul.md", "migliora te stesso", "auto-evolv", "temperatura modello",
            "run_custom_tool"
        )
    ) {
        addIfEnabled(ToolGroup.SELF_EVOLVE)
    }

    // 7. Background Automations
    if (p.containsAny("automazione", "pianifica", "ogni ora", "ogni giorno", "ricorrente", "cron")) {
        addIfEnabled(ToolGroup.AUTOMATIONS)
    }

    // 8. Memory & Skills (attached only when user asks to remember/forget/search memory,
    // shares personal profile/preferences, or explicitly uses/creates skills)
    val hasSlashSkillToken = Regex("""(?:^|\s)/[a-z][a-z0-9_-]{1,}""").containsMatchIn(p)
    if (hasSlashSkillToken || p.containsAny(
            "ricord", "segna", "annota", "tieni a mente", "memoriz", "dimentic", "cancella ricordo",
            "skill", "impara", "procedura", "mi chiamo", "abito ", "vivo a", "vivo in",
            "lavoro come", "faccio il", "faccio la", "preferisc", "mi piace",
            "cosa sai di me", "cosa ricordi", "abbiamo parlato", "sessione", "storico",
            "profilo", "vault", "user.md", "memory.md", "ogni volta", "da ora in poi",
            "rispondi sempre"
        )
    ) {
        addIfEnabled(ToolGroup.MEMORY)
    }

    return selected.take(2).toMutableSet().apply {
        if (accessibilityConnected && ToolGroup.PHONE_USE.id in enabledGroups) add(ToolGroup.PHONE_USE.id)
        if (ToolGroup.SELF_EVOLVE.id in enabledGroups && ToolGroup.SELF_EVOLVE.id !in this &&
            (p.containsAny("convert", "trasform", "estrai", "estrarre", "analizza", "formato", "csv", "json", "parser", "strumento", "tool", "elabora", "ordina", "raggruppa") || ToolGroup.CODING.id in this)) add("data_tools")
    }
}
