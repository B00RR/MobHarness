package com.example.agent.tools

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
    val argumentsSummary: String
)

class AgentApprovalCoordinator {
    private val _pendingApproval = MutableStateFlow<PendingToolApproval?>(null)
    val pendingApproval: StateFlow<PendingToolApproval?> = _pendingApproval.asStateFlow()

    private var currentDeferred: CompletableDeferred<Boolean>? = null

    suspend fun requestApproval(
        toolName: String,
        category: String,
        description: String,
        argumentsSummary: String
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        currentDeferred = deferred
        _pendingApproval.value = PendingToolApproval(
            toolName = toolName,
            category = category,
            description = description,
            argumentsSummary = argumentsSummary
        )
        return try {
            deferred.await()
        } finally {
            _pendingApproval.value = null
            currentDeferred = null
        }
    }

    fun resolve(approved: Boolean) {
        currentDeferred?.complete(approved)
    }
}

class ToolExecutionMonitor(
    private val repository: MobHarnRepository,
    private val approvalCoordinator: AgentApprovalCoordinator
) {
    @Volatile var activeSessionId: Long? = null
    @Volatile var activeProjectName: String = "starter_web"
    @Volatile var requireApprovalForExternal: Boolean = true
    @Volatile var onLiveTraceUpdate: ((ToolCallTrace) -> Unit)? = null

    fun executeTracked(
        toolName: String,
        category: String,
        argsSummary: String,
        isExternalOrMutating: Boolean = false,
        approvalDescription: String = "",
        block: suspend () -> String
    ): String = runBlocking {
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

        if (isExternalOrMutating && requireApprovalForExternal) {
            val granted = approvalCoordinator.requestApproval(
                toolName = toolName,
                category = category,
                description = approvalDescription.ifBlank { "Eseguire lo strumento $toolName?" },
                argumentsSummary = argsSummary
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

        val (ok, result) = runCatching { block() }.fold(
            onSuccess = { true to it },
            onFailure = { false to "Errore $toolName: ${it.message}" }
        )
        val elapsed = System.currentTimeMillis() - start
        onLiveTraceUpdate?.invoke(
            ToolCallTrace(
                id = traceId,
                toolName = toolName,
                category = category,
                argumentsSummary = argsSummary,
                resultSummary = result.take(500),
                durationMs = elapsed,
                status = if (ok) "success" else "error"
            )
        )
        repository.logToolExecution(activeSessionId, toolName, category, argsSummary, result, elapsed, ok)
        result
    }
}

class MemoryAndSkillsToolSet(
    private val repository: MobHarnRepository,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Salva o aggiorna un fatto persistente in USER.md o MEMORY.md (categorie: USER_PROFILE, PREFERENCE, FACT, PROJECT, CODING_CONVENTION)")
    fun save_memory(
        @ToolParam(description = "Categoria: USER_PROFILE, PREFERENCE, FACT, PROJECT o CODING_CONVENTION") category: String,
        @ToolParam(description = "Chiave breve univoca in snake_case") keyName: String,
        @ToolParam(description = "Contenuto chiaro e conciso del ricordo") content: String,
        @ToolParam(description = "Importanza da 1 a 5") importance: Int = 3
    ): String = monitor.executeTracked(
        toolName = "save_memory",
        category = ToolGroup.MEMORY.id,
        argsSummary = "[$category] $keyName = ${content.take(80)}"
    ) {
        repository.upsertMemoryAndSync(category, keyName, content, importance)
        "Memoria salvata e sincronizzata su Markdown: [$category] $keyName"
    }

    @Tool(description = "Cerca nei fatti persistenti di USER.md e MEMORY.md per parola chiave")
    fun search_memory(
        @ToolParam(description = "Parola chiave o tema da cercare") query: String
    ): String = monitor.executeTracked(
        toolName = "search_memory",
        category = ToolGroup.MEMORY.id,
        argsSummary = "query=\"$query\""
    ) {
        val results = repository.searchMemories(query)
        if (results.isEmpty()) "Nessuna voce di memoria trovata per '$query'."
        else results.take(12).joinToString("\n") { "- [${it.category}] ${it.keyName}: ${it.content}" }
    }

    @Tool(description = "Cerca nello storico completo di tutte le conversazioni passate tramite SQLite FTS")
    fun search_past_sessions(
        @ToolParam(description = "Parole chiave da cercare nelle vecchie chat") query: String
    ): String = monitor.executeTracked(
        toolName = "search_past_sessions",
        category = ToolGroup.MEMORY.id,
        argsSummary = "fts=\"$query\""
    ) {
        val hits = repository.searchSessionsHistory(query, limit = 8)
        if (hits.isEmpty()) "Nessun messaggio passato trovato per '$query'."
        else hits.joinToString("\n") { m ->
            "[Sessione #${m.sessionId} • ${m.role}]: ${m.content.take(220)}"
        }
    }

    @Tool(description = "Carica le istruzioni complete di una Skill procedurale per nome/slug")
    fun load_skill(
        @ToolParam(description = "Slug della skill (es. coding_workflow, daily_briefing)") slug: String
    ): String = monitor.executeTracked(
        toolName = "load_skill",
        category = ToolGroup.MEMORY.id,
        argsSummary = "slug=$slug"
    ) {
        val skill = repository.loadSkill(slug)
            ?: return@executeTracked "Skill '$slug' non trovata."
        "# Skill: ${skill.title} (/${skill.slug})\n${skill.instructionsMd}"
    }

    @Tool(description = "Salva o aggiorna una Skill procedurale riutilizzabile nella libreria locale")
    fun save_skill(
        @ToolParam(description = "Slug breve univoco (es. kotlin_refactor)") slug: String,
        @ToolParam(description = "Titolo descrittivo") title: String,
        @ToolParam(description = "Breve descrizione di quando usarla") description: String,
        @ToolParam(description = "Istruzioni passo-passo in Markdown") instructionsMd: String
    ): String = monitor.executeTracked(
        toolName = "save_skill",
        category = ToolGroup.MEMORY.id,
        argsSummary = "slug=$slug, title=$title"
    ) {
        repository.upsertSkill(slug, title, description, instructionsMd)
        "Skill '/$slug' salvata e sincronizzata nel vault."
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
        argsSummary = "delete \"$query\""
    ) {
        val hits = repository.searchMemories(query)
        val exact = hits.firstOrNull { it.keyName.equals(query.trim(), ignoreCase = true) } ?: hits.firstOrNull()
            ?: return@executeTracked "Nessun ricordo trovato per '$query'."
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
    val selfEvolveTools = SelfEvolveToolSet(repository, sandbox, monitor)
    val phoneUseTools = PhoneUseToolSet(monitor)

    /**
     * Selects a focused subset of enabled ToolGroups for the current prompt so edge models
     * like Gemma 4 E2B/E4B stay fast and accurate while ALWAYS keeping Memory & Skills ready
     * for continuous self-improvement.
     */
    fun selectRoutedToolGroups(userPrompt: String, enabledGroups: Set<String>): Set<String> {
        val p = userPrompt.lowercase()
        val domainSelected = linkedSetOf<String>()

        fun addDomainIfEnabled(group: ToolGroup) {
            if (group.id in enabledGroups) domainSelected.add(group.id)
        }

        // 1. Web Search / Live factual info / Math / URLs
        if (p.containsAny(
                "cerca", "ricerca", "internet", "web", "google", "online", "notizie", "news",
                "meteo", "tempo fa", "oggi", "ultime", "prezzo", "quanto costa", "chi è", "chi ha",
                "cos'è", "cosa è", "http://", "https://", "www.", "sito", "link", "pagina",
                "calcola", "quanto fa", "radice", "percentuale", "json", "regex", "wikipedia",
                "popolazione", "abitanti", "quanti", "quante", "quando", "dove si trova",
                "capitale", "classifica", "risultato", "cambio", "valore", "meteo"
            )
        ) {
            addDomainIfEnabled(ToolGroup.WEB_CALC)
        }

        // 2. Coding Workspace & Sandbox
        if (p.containsAny(
                "codice", "code", "file", "workspace", "progetto", "studio", "script", "javascript",
                "js", "sql", "sqlite", "html", "css", "kotlin", "python", "patch", "bug", "errore",
                "funzione", "sandbox", "esegui", "compila", "testa", "index.html", "app.js"
            )
        ) {
            addDomainIfEnabled(ToolGroup.CODING)
        }

        // 3. Phone-Use (Accessibility UI control)
        if (p.containsAny(
                "schermo", "screen", "clicca", "tocca", "premi", "tap", "scorri", "scroll",
                "digita", "scrivi nel campo", "leggi lo schermo", "cosa vedi", "accessibilit",
                "indietro", "home", "notifiche", "screenshot"
            )
        ) {
            addDomainIfEnabled(ToolGroup.PHONE_USE)
        }

        // 4. Android System Actions
        if (p.containsAny(
                "sveglia", "allarme", "timer", "calendario", "evento", "appuntamento", "mappa",
                "maps", "indicazioni", "apri", "aprir", "avvia", "lancia", "app", "applicazion",
                "youtube", "whatsapp", "spotify", "chrome", "instagram", "telegram", "fotocamera",
                "camera", "galleria", "foto", "calcolatrice", "orologio", "gmail", "contatti",
                "email", "mail", "sms", "messaggio", "chiama", "telefono", "condividi",
                "impostazioni", "wifi", "bluetooth"
            )
        ) {
            addDomainIfEnabled(ToolGroup.ANDROID_ACTIONS)
        }

        // 5. Device Telemetry & Sensors
        if (p.containsAny(
                "batteria", "ram", "memoria libera", "storage", "spazio", "temperatura", "cpu",
                "rete", "telemetria", "appunti", "clipboard", "sensore", "sensori", "luce",
                "barometro", "posizione", "dove sono", "gps", "pixel"
            )
        ) {
            addDomainIfEnabled(ToolGroup.TELEMETRY)
        }

        // 6. Self-Evolving Harness & Custom Tools
        if (p.containsAny(
                "harness", "custom tool", "nuovo tool", "crea tool", "crea uno strumento",
                "soul.md", "migliora te stesso", "auto-evolv", "ottimizza", "temperatura modello",
                "parametri", "run_custom_tool"
            )
        ) {
            addDomainIfEnabled(ToolGroup.SELF_EVOLVE)
        }

        // 7. Background Automations
        if (p.containsAny("automazione", "pianifica", "ogni ora", "ogni giorno", "ricorrente", "cron", "background")) {
            addDomainIfEnabled(ToolGroup.AUTOMATIONS)
        }

        // Default lean core when prompt is general or factual: Web/Calc + Telemetry
        if (domainSelected.isEmpty()) {
            addDomainIfEnabled(ToolGroup.WEB_CALC)
            addDomainIfEnabled(ToolGroup.TELEMETRY)
        }

        // Keep up to 2 domain groups + ALWAYS attach Memory & Skills (if enabled) so the agent
        // can save facts, search memories, load skills, and create new skills on every turn!
        val finalGroups = linkedSetOf<String>()
        finalGroups.addAll(domainSelected.take(2))
        if (ToolGroup.MEMORY.id in enabledGroups) {
            finalGroups.add(ToolGroup.MEMORY.id)
        }
        return finalGroups
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    fun buildEnabledToolProviders(enabledGroups: Set<String>): List<ToolProvider> {
        val providers = mutableListOf<ToolProvider>()
        if (ToolGroup.WEB_CALC.id in enabledGroups) providers.add(tool(webCalcTools))
        if (ToolGroup.MEMORY.id in enabledGroups) providers.add(tool(memoryTools))
        if (ToolGroup.CODING.id in enabledGroups) providers.add(tool(codingTools))
        if (ToolGroup.ANDROID_ACTIONS.id in enabledGroups) providers.add(tool(androidTools))
        if (ToolGroup.TELEMETRY.id in enabledGroups) providers.add(tool(telemetryTools))
        if (ToolGroup.AUTOMATIONS.id in enabledGroups) providers.add(tool(automationTools))
        if (ToolGroup.SELF_EVOLVE.id in enabledGroups) providers.add(tool(selfEvolveTools))
        if (ToolGroup.PHONE_USE.id in enabledGroups) providers.add(tool(phoneUseTools))
        return providers
    }

    /**
     * Direct tracked dispatcher used by LiteRtRuntimeManager's Tool-Call Recovery layer whenever
     * LiteRT-LM's strict C++ ANTLR parser fails on a slightly malformed call (e.g. `"` instead of `<|"|>`).
     */
    fun invokeToolByName(rawName: String, args: Map<String, Any?>): String? {
        val normalized = rawName.substringAfterLast(':').trim().lowercase()

        fun str(vararg keys: String, default: String = ""): String {
            for (k in keys) {
                val direct = args[k] ?: args.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                if (direct != null) {
                    val s = direct.toString().trim()
                    if (s.isNotEmpty()) return s
                }
            }
            if (args.size == 1 && keys.isNotEmpty()) {
                val single = args.values.firstOrNull()?.toString()?.trim()
                if (!single.isNullOrEmpty()) return single
            }
            return default
        }

        fun intArg(vararg keys: String, default: Int): Int {
            for (k in keys) {
                val raw = args[k] ?: args.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                when (raw) {
                    is Number -> return raw.toInt()
                    is String -> raw.trim().toIntOrNull()?.let { return it }
                }
            }
            return default
        }

        fun doubleArg(vararg keys: String, default: Double): Double {
            for (k in keys) {
                val raw = args[k] ?: args.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                when (raw) {
                    is Number -> return raw.toDouble()
                    is String -> raw.trim().toDoubleOrNull()?.let { return it }
                }
            }
            return default
        }

        fun boolArg(vararg keys: String, default: Boolean): Boolean {
            for (k in keys) {
                val raw = args[k] ?: args.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                when (raw) {
                    is Boolean -> return raw
                    is String -> when (raw.trim().lowercase()) {
                        "true", "1", "yes", "si" -> return true
                        "false", "0", "no" -> return false
                    }
                }
            }
            return default
        }

        return when (normalized) {
            // Web & Calc
            "web_search", "search_web", "google_search" ->
                webCalcTools.web_search(str("query", "q", "search_query", "text"))
            "fetch_webpage", "fetch_url", "read_webpage" ->
                webCalcTools.fetch_webpage(str("url", "link", "href"))
            "calculate_expression", "calculate", "calc" ->
                webCalcTools.calculate_expression(str("expression", "expr", "formula"))
            "validate_json_regex", "validate_json_or_regex" ->
                webCalcTools.validate_json_regex(
                    mode = str("mode", default = "json"),
                    input = str("input", "text", "data"),
                    pattern = str("pattern", "regex")
                )

            // Memory & Skills
            "save_memory", "remember_fact", "remember", "memorize" ->
                memoryTools.save_memory(
                    category = str("category", "cat", default = "FACT"),
                    keyName = str("keyName", "key_name", "key", default = "nota_${System.currentTimeMillis() % 10000}"),
                    content = str("content", "value", "fact", "text"),
                    importance = intArg("importance", "priority", default = 3)
                )
            "search_memory", "recall_memory", "get_memory" ->
                memoryTools.search_memory(str("query", "q", "keyword", "key"))
            "delete_memory", "forget_memory" ->
                memoryTools.delete_memory(str("query", "keyName", "key", "q"))
            "search_past_sessions", "search_history" ->
                memoryTools.search_past_sessions(str("query", "q", "keyword"))
            "list_skills" ->
                memoryTools.list_skills()
            "load_skill", "use_skill", "get_skill" ->
                memoryTools.load_skill(str("slug", "name", "skill"))
            "save_skill", "create_skill", "learn_skill" ->
                memoryTools.save_skill(
                    slug = str("slug", "name", default = "skill_${System.currentTimeMillis() % 10000}"),
                    title = str("title", "name", default = "Skill appresa"),
                    description = str("description", "desc", default = "Procedura appresa in chat"),
                    instructionsMd = str("instructionsMd", "instructions_md", "instructions", "content")
                )

            // Android System Actions
            "launch_app", "open_app", "start_app" ->
                androidTools.launch_app(str("appName", "app_name", "app", "name", "package"))
            "open_url", "open_link" ->
                androidTools.open_url(str("url", "link"))
            "open_maps", "maps" ->
                androidTools.open_maps(str("query", "location", "place", "address"))
            "set_alarm" ->
                androidTools.set_alarm(
                    hour = intArg("hour", "hours", "h", default = 8),
                    minute = intArg("minute", "minutes", "m", default = 0),
                    message = str("message", "label", "title", default = "MobHarn")
                )
            "set_timer", "start_timer" ->
                androidTools.set_timer(
                    seconds = intArg("seconds", "duration", "sec", default = 60),
                    message = str("message", "label", default = "Timer MobHarn")
                )
            "create_calendar_event" ->
                androidTools.create_calendar_event(
                    title = str("title", "name", default = "Evento"),
                    description = str("description", "notes"),
                    startInMinutes = intArg("startInMinutes", "start_in_minutes", default = 60),
                    durationMinutes = intArg("durationMinutes", "duration_minutes", default = 60)
                )
            "compose_email", "draft_email" ->
                androidTools.compose_email(
                    to = str("to", "recipient", "email"),
                    subject = str("subject", "title"),
                    body = str("body", "text", "message")
                )
            "compose_sms", "draft_sms" ->
                androidTools.compose_sms(
                    phoneNumber = str("phoneNumber", "phone_number", "to", "number"),
                    message = str("message", "body", "text")
                )
            "dial_number", "call_number" ->
                androidTools.dial_number(str("phoneNumber", "phone_number", "number"))
            "share_text" ->
                androidTools.share_text(str("text", "content", "message"))
            "open_system_settings", "open_settings" ->
                androidTools.open_system_settings(str("section", "setting", default = "general"))

            // Coding Workspace & Sandbox
            "list_workspace_files", "list_files" ->
                codingTools.list_workspace_files()
            "read_file_lines", "read_file" ->
                codingTools.read_file_lines(
                    relativePath = str("relativePath", "relative_path", "path", "file"),
                    startLine = intArg("startLine", "start_line", default = 1),
                    endLine = intArg("endLine", "end_line", default = 150)
                )
            "write_workspace_file", "write_file" ->
                codingTools.write_workspace_file(
                    relativePath = str("relativePath", "relative_path", "path", "file"),
                    content = str("content", "code", "text"),
                    summary = str("summary", default = "Aggiornato da Gemma 4")
                )
            "patch_workspace_file", "patch_file" ->
                codingTools.patch_workspace_file(
                    relativePath = str("relativePath", "relative_path", "path", "file"),
                    oldText = str("oldText", "old_text", "search"),
                    newText = str("newText", "new_text", "replace")
                )
            "search_code", "grep_workspace" ->
                codingTools.search_code(str("pattern", "query", "q"))
            "run_js_sandbox", "run_javascript", "run_js" ->
                codingTools.run_js_sandbox(str("code", "script", "js"))
            "run_sql_sandbox", "run_sql" ->
                codingTools.run_sql_sandbox(str("sqlScript", "sql_script", "sql", "query"))

            // Telemetry
            "get_device_telemetry", "device_telemetry", "telemetry" ->
                telemetryTools.get_device_telemetry()
            "read_clipboard", "get_clipboard" ->
                telemetryTools.read_clipboard()
            "get_sensor_snapshot", "get_sensors" ->
                telemetryTools.get_sensor_snapshot()
            "get_approx_location", "get_location" ->
                telemetryTools.get_approx_location()

            // Automations
            "schedule_automation" ->
                automationTools.schedule_automation(
                    name = str("name", "title", default = "Automazione"),
                    prompt = str("prompt", "instruction", "query"),
                    actionType = str("actionType", "action_type", default = "BRIEFING"),
                    intervalMinutes = intArg("intervalMinutes", "interval_minutes", default = 60),
                    isPeriodic = boolArg("isPeriodic", "is_periodic", default = true)
                )

            // Self-Evolve
            "create_custom_tool" ->
                selfEvolveTools.create_custom_tool(
                    name = str("name"),
                    description = str("description"),
                    parametersDoc = str("parametersDoc", "parameters_doc", default = "inputJson"),
                    jsCode = str("jsCode", "js_code", "code")
                )
            "list_custom_tools" ->
                selfEvolveTools.list_custom_tools()
            "run_custom_tool" ->
                selfEvolveTools.run_custom_tool(
                    name = str("name"),
                    inputJson = str("inputJson", "input_json", "input", default = "{}")
                )
            "delete_custom_tool" ->
                selfEvolveTools.delete_custom_tool(str("name"))
            "update_soul_md" ->
                selfEvolveTools.update_soul_md(
                    mode = str("mode", default = "append"),
                    content = str("content", "text"),
                    rationale = str("rationale", default = "Miglioramento harness")
                )
            "tune_harness_settings", "tune_harness_params" ->
                selfEvolveTools.tune_harness_settings(
                    temperature = doubleArg("temperature", default = -1.0),
                    topP = doubleArg("topP", "top_p", default = -1.0),
                    maxNumTokens = intArg("maxNumTokens", "max_num_tokens", default = -1),
                    activeProject = str("activeProject", "active_project"),
                    enableToolGroup = str("enableToolGroup", "enable_tool_group")
                )

            // Phone-Use
            "read_screen_ui", "read_screen" ->
                phoneUseTools.read_screen_ui(intArg("maxNodes", "max_nodes", default = 80))
            "tap_screen_text", "tap_element" ->
                phoneUseTools.tap_screen_text(str("targetText", "target_text", "text", "label"))
            "tap_screen_coords", "tap_coordinates" ->
                phoneUseTools.tap_screen_coords(
                    x = intArg("x", default = 0),
                    y = intArg("y", default = 0)
                )
            "type_screen_text", "type_text" ->
                phoneUseTools.type_screen_text(
                    fieldHint = str("fieldHint", "field_hint", "hint"),
                    text = str("text", "value")
                )
            "scroll_screen" ->
                phoneUseTools.scroll_screen(str("direction", default = "down"))
            "phone_global_action", "system_navigate" ->
                phoneUseTools.phone_global_action(str("action", "command", default = "home"))

            else -> null
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
