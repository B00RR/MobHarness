package com.example.agent.tools

import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.agent.sandbox.CodeSandboxRunner
import com.example.data.MobHarnRepository
import com.example.data.prefs.ToolGroup
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONObject

class DataToolSet(
    private val repository: MobHarnRepository,
    private val sandbox: CodeSandboxRunner,
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    @Tool(description = "Prova un esempio e propone di salvare un nuovo Custom Tool permanente in JavaScript ES6+ nell'harness. Il codice riceve la variabile globale INPUT (JSON parsato o stringa) e può usare console.log e return.")
    fun create_custom_tool(
        @ToolParam(description = "Nome univoco in snake_case (es. csv_parser, roi_calc)") name: String,
        @ToolParam(description = "Descrizione chiara di cosa fa il tool") description: String,
        @ToolParam(description = "Descrizione del formato di inputJson atteso") parametersDoc: String = "inputJson: JSON o stringa opzionale",
        @ToolParam(description = "Corpo JavaScript ES6+ che usa INPUT e termina con return ...") jsCode: String,
        @ToolParam(description = "Input di esempio in formato JSON") exampleInput: String,
        @ToolParam(description = "Valore JSON atteso compatto, per esempio 42 oppure [1,2]") expectedJson: String
    ): String = monitor.executeTracked(
        toolName = "create_custom_tool",
        category = ToolGroup.SELF_EVOLVE.id,
        argsSummary = "$name: ${description.take(70)}",
        isExternalOrMutating = true,
        verifyBeforeSaving = {
            when {
                !Regex("[a-z][a-z0-9_]{0,39}").matches(name) -> "Nome tool non valido."
                repository.getCustomToolByName(name) != null -> "Nome già registrato: riusa il tool, non sovrascriverlo."
                jsCode.length !in 1..16000 || exampleInput.length !in 1..2000 || expectedJson.length !in 1..2000 -> "Fornisci codice e un esempio breve con risultato JSON atteso."
                else -> {
                    val wrapped = SelfEvolveToolSet.buildCustomToolWrapper(jsCode, exampleInput)
                    val probe = "return JSON.stringify(JSON.parse(" + JSONObject.quote(exampleInput) + ")) !== undefined ? JSON.stringify((function(){" + wrapped + "})()) : undefined;"
                    val result = sandbox.runJavaScript(probe)
                    if (result.success && matchesExample(result.output, expectedJson)) null else "Test del tool fallito: risultato diverso dall'esempio o codice non eseguibile. Non salvato."
                }
            }
        },
        approvalDescription = "Test superato. Salvare '$name' (${description.take(100)})?"
    ) {
        val id = repository.upsertCustomTool(
            name = name,
            description = description,
            parametersDoc = parametersDoc,
            jsCode = jsCode,
            createdByAgent = true, insertOnly = true
        )
        check(id != -1L) { "Nome già registrato. Nessuna sostituzione." }
        "Custom Tool '$name' verificato e registrato stabilmente nell'harness! Ora puoi eseguirlo con run_custom_tool."
    }

    @Tool(description = "Elenca tutti i Custom Tool JS creati nell'harness")
    fun list_custom_tools(): String = monitor.executeTracked(
        toolName = "list_custom_tools",
        category = ToolGroup.SELF_EVOLVE.id,
        argsSummary = "list_custom_tools"
    ) {
        val list = repository.getEnabledCustomTools()
        if (list.isEmpty()) "Nessun Custom Tool registrato."
        else list.joinToString("\n") { "- ${it.name} (usato ${it.usageCount}x): ${it.description} [${it.parametersDoc}]" }
    }

    @Tool(description = "Esegue un Custom Tool JS registrato passando opzionalmente inputJson")
    fun run_custom_tool(
        @ToolParam(description = "Nome del custom tool da eseguire") name: String,
        @ToolParam(description = "Input in formato JSON o testo semplice") inputJson: String = "{}"
    ): String = monitor.executeTracked(
        toolName = "run_custom_tool",
        category = ToolGroup.SELF_EVOLVE.id,
        argsSummary = "$name(${inputJson.take(60)})"
    ) {
        val tool = repository.getCustomToolByName(name)
            ?: return@executeTracked "Custom Tool '$name' non trovato. Usa list_custom_tools per vedere quelli disponibili."
        if (!tool.enabled) return@executeTracked "Custom Tool '$name' è attualmente disabilitato."
        val wrapped = SelfEvolveToolSet.buildCustomToolWrapper(tool.jsCode, inputJson)
        val res = sandbox.runJavaScript(wrapped)
        repository.incrementCustomToolUsage(tool.name)
        val status = if (res.success) "OK" else "ERRORE"
        "[CustomTool ${tool.name} • $status • ${res.durationMs}ms]\n${res.output}"
    }

    companion object {
        internal fun matchesExample(output: String, expected: String): Boolean {
            if (!output.startsWith("=> ")) return false
            // Compare canonical compact JSON, never the console log or a substring of it.
            return output.removePrefix("=> ").trim() == expected.trim()
        }
    }
}

class SelfEvolveToolSet(
    private val repository: MobHarnRepository,
    private val sandbox: CodeSandboxRunner,
    private val monitor: ToolExecutionMonitor
) : ToolSet {
    @Tool(description = "Elimina un Custom Tool JS dall'harness per nome")
    fun delete_custom_tool(
        @ToolParam(description = "Nome del custom tool da eliminare") name: String
    ): String = monitor.executeTracked(
        toolName = "delete_custom_tool",
        category = ToolGroup.SELF_EVOLVE.id,
        argsSummary = "delete $name",
        isExternalOrMutating = true,
        approvalDescription = "Eliminare il Custom Tool '$name'?"
    ) {
        val removed = repository.deleteCustomToolByName(name)
        if (removed > 0) "Custom Tool '$name' eliminato."
        else "Custom Tool '$name' non trovato."
    }

    @Tool(description = "Evolve le istruzioni di sistema SOUL.md dell'agente (mode: 'append' per aggiungere una regola o 'replace' per riscriverlo)")
    fun update_soul_md(
        @ToolParam(description = "'append' oppure 'replace'") mode: String,
        @ToolParam(description = "Testo Markdown da aggiungere o sostituire in SOUL.md") content: String,
        @ToolParam(description = "Motivo del miglioramento") rationale: String = "Miglioramento harness"
    ): String = monitor.executeTracked(
        toolName = "update_soul_md",
        category = ToolGroup.SELF_EVOLVE.id,
        argsSummary = "mode=$mode • ${rationale.take(70)}",
        isExternalOrMutating = true,
        approvalDescription = "Aggiornare SOUL.md ($mode)? Motivo: $rationale"
    ) {
        val current = repository.readVault().soulMd
        val updated = if (mode.equals("replace", ignoreCase = true)) {
            content.trim()
        } else {
            "${current.trimEnd()}\n\n${content.trim()}"
        }
        repository.saveSoulMd(updated)
        "SOUL.md aggiornato ($mode). Le nuove istruzioni saranno attive dal prossimo turno/sessione."
    }

    @Tool(description = "Ottimizza i parametri dell'harness: temperatura, topP, maxNumTokens del modello attivo, progetto coding attivo o gruppi di tool")
    fun tune_harness_settings(
        @ToolParam(description = "Nuova temperatura 0.0-1.5, oppure -1 per non cambiare") temperature: Double = -1.0,
        @ToolParam(description = "Nuovo topP 0.1-1.0, oppure -1 per non cambiare") topP: Double = -1.0,
        @ToolParam(description = "Nuova finestra contesto (4096/8192/16384/32768), oppure -1 per non cambiare") maxNumTokens: Int = -1,
        @ToolParam(description = "Nome progetto workspace da attivare, oppure vuoto") activeProject: String = "",
        @ToolParam(description = "ID gruppo tool da attivare (coding, memory, android_actions, web_calc, vision, automations, telemetry, self_evolve, phone_use), oppure vuoto") enableToolGroup: String = ""
    ): String = monitor.executeTracked(
        toolName = "tune_harness_settings",
        category = ToolGroup.SELF_EVOLVE.id,
        argsSummary = "temp=$temperature, topP=$topP, ctx=$maxNumTokens, proj=$activeProject",
        isExternalOrMutating = true,
        approvalDescription = "Applicare le modifiche ai parametri dell'harness proposte da Gemma 4?"
    ) {
        val changes = mutableListOf<String>()
        val activeModel = repository.getActiveModelProfile()
        if (activeModel != null && (temperature >= 0.0 || topP > 0.0 || maxNumTokens >= 1024)) {
            val updated = activeModel.copy(
                temperature = if (temperature in 0.0..1.5) temperature.toFloat() else activeModel.temperature,
                topP = if (topP in 0.1..1.0) topP.toFloat() else activeModel.topP,
                maxNumTokens = if (maxNumTokens >= 1024) maxNumTokens.coerceIn(1024, 32768) else activeModel.maxNumTokens
            )
            repository.updateModelProfile(updated)
            changes.add("Modello '${updated.displayName}': temp=${updated.temperature}, topP=${updated.topP}, ctx=${updated.maxNumTokens}")
        }
        if (activeProject.isNotBlank()) {
            val created = repository.workspace.createProject(activeProject)
            repository.prefs.setActiveProject(created)
            monitor.activeProjectName = created
            changes.add("Progetto attivo impostato su '$created'")
        }
        if (enableToolGroup.isNotBlank()) {
            val valid = ToolGroup.entries.firstOrNull { it.id.equals(enableToolGroup.trim(), true) }
            if (valid != null) {
                repository.prefs.toggleToolGroup(valid.id, true)
                changes.add("Gruppo tool '${valid.label}' abilitato")
            }
        }
        if (changes.isEmpty()) "Nessun parametro valido modificato."
        else "Harness ottimizzato:\n- " + changes.joinToString("\n- ")
    }

    companion object {
        fun buildCustomToolWrapper(jsCode: String, inputJson: String): String {
            val quotedInput = JSONObject.quote(inputJson)
            return """
                let INPUT;
                try {
                  INPUT = JSON.parse($quotedInput);
                } catch (_e) {
                  INPUT = $quotedInput;
                }
                const __fn = new Function('INPUT', ${JSONObject.quote(jsCode)});
                return __fn(INPUT);
            """.trimIndent()
        }
    }
}

class PhoneUseToolSet(
    private val monitor: ToolExecutionMonitor
) : ToolSet {

    private val accessRequired = "Servizio Accessibilità non connesso. Nella chat scegli ⋮ → Apri Accessibilità per autorizzare MobHarn."

    private fun requireService(): MobHarnAccessibilityService? = MobHarnAccessibilityService.instance

    @Tool(description = "Legge l'albero UI reale dello schermo attualmente visibile sul Pixel (app aperta, testi, bottoni cliccabili, campi input e coordinate)")
    fun read_screen_ui(
        @ToolParam(description = "Numero massimo di elementi UI da restituire (default 80)") maxNodes: Int = 80
    ): String = monitor.executeTracked(
        toolName = "read_screen_ui",
        category = ToolGroup.PHONE_USE.id,
        argsSummary = "maxNodes=$maxNodes"
    ) {
        val svc = checkNotNull(requireService()) { accessRequired }
        svc.captureScreenTree(maxNodes.coerceIn(10, 150))
    }

    @Tool(description = "Esegue un tap su un elemento visibile sullo schermo cercandolo per testo o etichetta")
    fun tap_screen_text(
        @ToolParam(description = "Testo o descrizione esatta del pulsante da toccare") targetText: String
    ): String {
        val source = requireService()
        val target = source?.prepare("text", targetText)
        return monitor.executeTracked(
            toolName = "tap_screen_text",
            category = ToolGroup.PHONE_USE.id,
            argsSummary = "tap \"$targetText\"",
            isExternalOrMutating = true,
            approvalDescription = "Toccare sullo schermo l'elemento '$targetText'?"
        ) {
            checkNotNull(source) { accessRequired }.applyTarget(target)
        }
    }

    @Tool(description = "Tocca il nodo accessibile al centro (x,y) riportato dalla lettura; nessun gesto su punti non identificati")
    fun tap_screen_coords(
        @ToolParam(description = "Coordinata X in pixel") x: Int,
        @ToolParam(description = "Coordinata Y in pixel") y: Int
    ): String {
        val source = requireService()
        val target = source?.prepare("point", x = x, y = y)
        return monitor.executeTracked(
            toolName = "tap_screen_coords",
            category = ToolGroup.PHONE_USE.id,
            argsSummary = "tap @($x, $y)",
            isExternalOrMutating = target != null, // A missing target only reports an error; never request a blind tap.
            approvalDescription = "Toccare lo schermo alle coordinate ($x, $y)?"
        ) {
            checkNotNull(source) { accessRequired }.applyTarget(target)
        }
    }

    @Tool(description = "Scrive del testo nel campo di input attivo o corrispondente a fieldHint sullo schermo")
    fun type_screen_text(
        @ToolParam(description = "Suggerimento/etichetta del campo (o vuoto per campo attivo)") fieldHint: String = "",
        @ToolParam(description = "Testo da inserire") text: String
    ): String {
        val targetField = if (fieldHint.isNotBlank()) "'$fieldHint'" else "campo attivo"
        val source = requireService()
        val target = source?.prepare("input", fieldHint)
        return monitor.executeTracked(
            toolName = "type_screen_text",
            category = ToolGroup.PHONE_USE.id,
            argsSummary = if (fieldHint.isNotBlank()) "type in '$fieldHint'" else "type in active field",
            isExternalOrMutating = true,
            approvalDescription = "Scrivere '${text.take(60)}' nel $targetField sullo schermo?"
        ) {
            checkNotNull(source) { accessRequired }.applyTarget(target, text)
        }
    }

    @Tool(description = "Scorre lo schermo in una direzione: 'down', 'up', 'left' o 'right'")
    fun scroll_screen(
        @ToolParam(description = "Direzione: down, up, left o right") direction: String = "down"
    ): String {
        val source = requireService()
        val target = source?.prepare("scroll")
        return monitor.executeTracked(
            toolName = "scroll_screen",
            category = ToolGroup.PHONE_USE.id,
            argsSummary = "scroll $direction",
            isExternalOrMutating = true,
            approvalDescription = "Scorrere lo schermo verso '$direction'?"
        ) {
            checkNotNull(source) { accessRequired }.applyTarget(target, direction)
        }
    }

    @Tool(description = "Esegue un'azione globale di navigazione Android: 'back', 'home', 'recents', 'notifications', 'quick_settings', 'screenshot'")
    fun phone_global_action(
        @ToolParam(description = "Azione: back, home, recents, notifications, quick_settings o screenshot") action: String
    ): String = monitor.executeTracked(
        toolName = "phone_global_action",
        category = ToolGroup.PHONE_USE.id,
        exactCommand = when (action) { "recents", "recenti" -> listOf("recents"); "home" -> listOf("home"); else -> null },
        argsSummary = "global $action",
        isExternalOrMutating = true,
        approvalDescription = "Eseguire l'azione di sistema '$action' sul Pixel?"
    ) {
        val svc = requireService()
            ?: return@executeTracked accessRequired
        if (action.trim().lowercase() in setOf("recents", "recenti")) svc.openVerifiedRecents()
        else svc.performSystemNavigation(action)
    }
}
