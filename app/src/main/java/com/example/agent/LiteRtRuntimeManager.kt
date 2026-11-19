package com.example.agent

import android.content.Context
import android.os.Process
import com.example.agent.tools.MobHarnToolkitRegistry
import com.example.data.db.ModelProfileEntity
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

sealed interface EngineStatus {
    data object Idle : EngineStatus
    data class Loading(val modelName: String, val backendLabel: String) : EngineStatus
    data class Ready(
        val model: ModelProfileEntity,
        val activeBackend: String,
        val visionEnabled: Boolean,
        val loadTimeMs: Long
    ) : EngineStatus
    data class Error(val message: String, val detail: String = "") : EngineStatus
}

data class StreamChunk(
    val visibleDelta: String = "",
    val thinkingDelta: String = "",
    val tokensPerSec: Float = 0f,
    val ttftMs: Long = 0L,
    val thermalNote: String? = null
)

class LiteRtRuntimeManager(
    private val context: Context,
    private val toolkitRegistry: MobHarnToolkitRegistry
) {
    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mobharn-litert-engine").apply { isDaemon = true }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val housekeepingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null
    private var loadedProfile: ModelProfileEntity? = null
    private var lastAutoUnloadedProfile: ModelProfileEntity? = null
    private var activeBackendLabel: String = "GPU"
    private var activeSessionConversationId: Long = -1L
    private var activeToolGroupsSignature: Set<String> = emptySet()
    @Volatile private var conversationNeedsReset: Boolean = false
    @Volatile private var isTurnActive: Boolean = false

    private var idleKvTrimJob: Job? = null
    private var backgroundUnloadJob: Job? = null

    private data class LoadAttempt(
        val label: String,
        val backend: Backend,
        val visionBackend: Backend?,
        val maxNumTokens: Int?,
        val cacheDir: String?
    )

    fun canAutoReload(): Boolean = engine == null && lastAutoUnloadedProfile != null

    fun setLoadedProfileThinking(enabled: Boolean) {
        val current = loadedProfile ?: return
        if (current.thinkingEnabled == enabled) return
        val updated = current.copy(thinkingEnabled = enabled)
        loadedProfile = updated
        val currentStatus = _status.value
        if (currentStatus is EngineStatus.Ready && currentStatus.model.id == updated.id) {
            _status.value = currentStatus.copy(model = updated)
        }
    }

    suspend fun autoReloadIfNeeded(ecoThermalMode: Boolean = true): Boolean {
        if (engine != null) return true
        val saved = lastAutoUnloadedProfile ?: return false
        return loadModel(saved, ecoThermalMode = ecoThermalMode).isSuccess
    }

    suspend fun loadModel(
        profile: ModelProfileEntity,
        ecoThermalMode: Boolean = true
    ): Result<Unit> = withContext(engineDispatcher) {
        cancelHousekeepingTimers()
        val file = File(profile.filePath)
        if (!file.exists()) {
            val err = "File modello non trovato: ${profile.filePath}"
            _status.value = EngineStatus.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val lowerName = file.name.lowercase()
        val fileSizeMb = file.length() / (1024.0 * 1024.0)

        if (lowerName.contains("-web.") || lowerName.contains("_web.") || lowerName.endsWith("-web.litertlm") || lowerName.endsWith("-web.task")) {
            val msg = "File '${file.name}' è compilato per il browser (WebGPU), non per Android nativo."
            val detail = "Scarica da Hugging Face la variante Android standard 'gemma-4-E2B-it.litertlm' (GPU/CPU) oppure 'gemma-4-E4B-it.litertlm', NON il file '-web'."
            _status.value = EngineStatus.Error(msg, detail)
            return@withContext Result.failure(IllegalArgumentException("$msg $detail"))
        }
        if (file.length() < 10L * 1024L * 1024L) {
            val msg = "File '${file.name}' troppo piccolo (${String.format(java.util.Locale.US, "%.1f", fileSizeMb)} MB): download incompleto o file non valido."
            _status.value = EngineStatus.Error(msg, "Un modello Gemma 4 .litertlm pesa circa 2.5–3.7 GB.")
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        unloadInternal(rememberForAutoReload = false)

        val cacheDir = File(context.cacheDir, "litertlm_cache").apply { mkdirs() }.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val hasNativeLibDir = !nativeLibDir.isNullOrBlank() && File(nativeLibDir).exists()

        val looksLikeNpuFile = lowerName.contains("tensor") ||
            lowerName.contains("_g5") ||
            lowerName.contains("npu") ||
            lowerName.contains("qualcomm") ||
            lowerName.contains("mediatek")

        val pref = when {
            profile.preferredBackend.equals("NPU", ignoreCase = true) -> "NPU"
            profile.preferredBackend.equals("CPU", ignoreCase = true) -> "CPU"
            looksLikeNpuFile -> "NPU"
            else -> "GPU"
        }

        // In Eco mode, cap excessive KV-cache allocation to 8192 tokens unless user explicitly disables Eco
        val requestedCtx = if (ecoThermalMode) {
            profile.maxNumTokens.coerceIn(2048, 8192)
        } else {
            profile.maxNumTokens.coerceIn(1024, 32768)
        }
        val wantVision = profile.supportsVision

        val attempts = buildList {
            fun addNpuVariants() {
                if (!hasNativeLibDir) return
                val npuBackend = Backend.NPU(nativeLibraryDir = nativeLibDir)
                if (wantVision) {
                    if (ecoThermalMode) {
                        add(LoadAttempt("NPU Tensor + Vision CPU (Eco)", npuBackend, Backend.CPU(), requestedCtx, cacheDir))
                    } else {
                        add(LoadAttempt("NPU Tensor + Vision GPU", npuBackend, Backend.GPU(), requestedCtx, cacheDir))
                    }
                }
                add(LoadAttempt("NPU Tensor (solo testo)", npuBackend, null, requestedCtx, cacheDir))
                add(LoadAttempt("NPU Tensor (ctx default)", npuBackend, null, null, ":nocache"))
            }

            fun addGpuVariants() {
                if (wantVision) {
                    if (ecoThermalMode) {
                        // Vision on CPU avoids reserving ~500 MB extra GPU VRAM and lowers GPU thermal load
                        add(LoadAttempt("GPU + Vision CPU (Eco)", Backend.GPU(), Backend.CPU(), requestedCtx, cacheDir))
                        add(LoadAttempt("GPU + Vision GPU", Backend.GPU(), Backend.GPU(), requestedCtx, cacheDir))
                    } else {
                        add(LoadAttempt("GPU + Vision GPU", Backend.GPU(), Backend.GPU(), requestedCtx, cacheDir))
                        add(LoadAttempt("GPU + Vision CPU", Backend.GPU(), Backend.CPU(), requestedCtx, cacheDir))
                    }
                }
                add(LoadAttempt("GPU (solo testo)", Backend.GPU(), null, requestedCtx, cacheDir))
                add(LoadAttempt("GPU (ctx default, no-cache)", Backend.GPU(), null, null, ":nocache"))
            }

            fun addCpuVariants() {
                if (wantVision) {
                    add(LoadAttempt("CPU + Vision CPU", Backend.CPU(), Backend.CPU(), requestedCtx.coerceAtMost(8192), cacheDir))
                }
                add(LoadAttempt("CPU (solo testo)", Backend.CPU(), null, requestedCtx.coerceAtMost(8192), cacheDir))
                add(LoadAttempt("CPU (ctx default, no-cache)", Backend.CPU(), null, null, ":nocache"))
            }

            when (pref) {
                "NPU" -> {
                    addNpuVariants()
                    addGpuVariants()
                    addCpuVariants()
                }
                "CPU" -> {
                    addCpuVariants()
                    addNpuVariants()
                }
                else -> {
                    addGpuVariants()
                    addNpuVariants()
                    addCpuVariants()
                }
            }
        }

        val startMs = System.currentTimeMillis()
        val attemptErrors = mutableListOf<String>()
        var initializedEngine: Engine? = null
        var winningAttempt: LoadAttempt? = null

        for (attempt in attempts) {
            _status.value = EngineStatus.Loading(profile.displayName, attempt.label)
            val result = runCatching {
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = attempt.backend,
                    visionBackend = attempt.visionBackend,
                    maxNumTokens = attempt.maxNumTokens,
                    cacheDir = attempt.cacheDir
                )
                Engine(config).also { it.initialize() }
            }
            if (result.isSuccess) {
                initializedEngine = result.getOrThrow()
                winningAttempt = attempt
                break
            } else {
                val rawErr = result.exceptionOrNull()?.message ?: "Errore sconosciuto"
                val shortErr = rawErr.lines().lastOrNull { it.isNotBlank() } ?: rawErr.take(180)
                attemptErrors.add("• ${attempt.label}: $shortErr")
            }
        }

        if (initializedEngine == null || winningAttempt == null) {
            val allErrorsText = attemptErrors.joinToString("\n")
            val isTensorMismatch = allErrorsText.contains("Input tensor not found", ignoreCase = true)
            val isTaskFile = lowerName.endsWith(".task")
            val hint = buildString {
                append("File: ${file.name} (${String.format(java.util.Locale.US, "%.2f", fileSizeMb / 1024.0)} GB)\n")
                if (isTaskFile) {
                    append("Causa probabile: i file '.task' di MediaPipe legacy non sempre sono compatibili con LiteRT-LM. Usa un file '.litertlm'.\n")
                } else if (isTensorMismatch) {
                    append("Causa 'Input tensor not found': questo .litertlm è compilato per NPU/WebGPU o un altro SoC.\n")
                    append("Soluzione consigliata per Pixel 10 Pro XL: scarica 'gemma-4-E2B-it.litertlm' o 'gemma-4-E4B-it.litertlm' (variante standard GPU/CPU, NON '_Google_Tensor_G5' e NON '-web').\n")
                }
                append("\nTentativi eseguiti:\n")
                append(allErrorsText)
            }
            val summaryMsg = if (isTensorMismatch) {
                "Firma tensori incompatibile ('Input tensor not found') per '${file.name}'. Vedi diagnostica qui sotto."
            } else {
                "Impossibile inizializzare '${file.name}' su NPU/GPU/CPU."
            }
            _status.value = EngineStatus.Error(summaryMsg, hint)
            return@withContext Result.failure(IllegalStateException(summaryMsg))
        }

        engine = initializedEngine
        loadedProfile = profile
        lastAutoUnloadedProfile = null
        activeBackendLabel = winningAttempt.label
        val elapsed = System.currentTimeMillis() - startMs
        _status.value = EngineStatus.Ready(
            model = profile,
            activeBackend = activeBackendLabel,
            visionEnabled = winningAttempt.visionBackend != null,
            loadTimeMs = elapsed
        )
        Result.success(Unit)
    }

    suspend fun unloadModel() = withContext(engineDispatcher) {
        cancelHousekeepingTimers()
        unloadInternal(rememberForAutoReload = false)
        _status.value = EngineStatus.Idle
    }

    suspend fun resetConversation() = withContext(engineDispatcher) {
        closeConversationInternal()
    }

    /**
     * Releases only the active Conversation KV-cache (hundreds of MB of VRAM)
     * while keeping the compiled model Engine ready for instant next-turn re-seeding.
     */
    fun trimConversationCacheAsync() {
        if (isTurnActive || activeConversation == null) return
        housekeepingScope.launch(engineDispatcher) {
            if (!isTurnActive) {
                closeConversationInternal()
            }
        }
    }

    /**
     * Releases the entire LiteRT-LM Engine from RAM/VRAM under memory pressure or background timeout,
     * remembering the profile so the next user message can reload transparently.
     */
    fun autoUnloadForMemoryAsync() {
        if (isTurnActive || engine == null) return
        housekeepingScope.launch(engineDispatcher) {
            if (!isTurnActive && engine != null) {
                unloadInternal(rememberForAutoReload = true)
                _status.value = EngineStatus.Idle
            }
        }
    }

    fun onAppBackgrounded(autoUnloadEnabled: Boolean) {
        // Release Conversation KV-cache after 12s in background
        idleKvTrimJob?.cancel()
        idleKvTrimJob = housekeepingScope.launch {
            delay(12_000L)
            trimConversationCacheAsync()
        }
        // If enabled, release full model weights faster when battery is low or Power Save is active
        backgroundUnloadJob?.cancel()
        if (autoUnloadEnabled && engine != null) {
            val snap = runCatching { ThermalAndMemoryGuard.readSnapshot(context) }.getOrNull()
            val unloadDelayMs = if (snap?.isBatteryConstrained == true) 45_000L else 90_000L
            backgroundUnloadJob = housekeepingScope.launch {
                delay(unloadDelayMs)
                autoUnloadForMemoryAsync()
            }
        }
    }

    fun onAppForegrounded() {
        backgroundUnloadJob?.cancel()
        backgroundUnloadJob = null
    }

    private fun scheduleIdleKvTrim() {
        idleKvTrimJob?.cancel()
        idleKvTrimJob = housekeepingScope.launch {
            delay(60_000L)
            trimConversationCacheAsync()
        }
    }

    private fun cancelHousekeepingTimers() {
        idleKvTrimJob?.cancel()
        idleKvTrimJob = null
        backgroundUnloadJob?.cancel()
        backgroundUnloadJob = null
    }

    fun cancelGeneration() {
        conversationNeedsReset = true
        runCatching { activeConversation?.cancelProcess() }
    }

    fun streamTurn(
        sessionId: Long,
        systemInstruction: String,
        userPrompt: String,
        imageFilePath: String?,
        enabledToolGroups: Set<String>,
        recentHistory: List<Pair<String, String>> = emptyList(),
        ecoThermalMode: Boolean = true
    ): Flow<StreamChunk> = flow {
        val currentEngine = engine
            ?: throw IllegalStateException("Nessun modello Gemma 4 caricato. Importa e carica un file .litertlm nella scheda Modello.")
        val profile = loadedProfile
            ?: throw IllegalStateException("Profilo modello non inizializzato.")

        // Assess live device thermal & memory state before starting inference
        val thermalSnapshot = ThermalAndMemoryGuard.readSnapshot(context)
        val policy = ThermalAndMemoryGuard.computePacingPolicy(
            snapshot = thermalSnapshot,
            ecoModeEnabled = ecoThermalMode,
            userRequestedThinking = profile.thinkingEnabled
        )

        if (policy.pauseForOverheat) {
            throw IllegalStateException(
                "Protezione termica attiva: il dispositivo è a ${thermalSnapshot.batteryTempC}°C (${thermalSnapshot.thermalLevel.labelIt}). Attendi 1–2 minuti di raffreddamento prima di continuare."
            )
        }

        // Prevent Pixel Tensor prime CPU cores from pegging at max voltage during GPU sync
        runCatching { Process.setThreadPriority(policy.threadNicePriority) }

        idleKvTrimJob?.cancel()
        backgroundUnloadJob?.cancel()
        isTurnActive = true

        try {
            // Check if existing KV cache is near saturation (>70% of context) or dirty
            val existingConv = activeConversation
            val tokenLimit = (if (ecoThermalMode) profile.maxNumTokens.coerceAtMost(8192) else profile.maxNumTokens)
                .coerceIn(2048, 32768)
            val currentTokens = if (existingConv != null && runCatching { existingConv.isAlive }.getOrDefault(false)) {
                runCatching { existingConv.getTokenCount() }.getOrDefault(0)
            } else {
                Int.MAX_VALUE
            }
            val kvNearFull = currentTokens >= (tokenLimit * 0.70f).toInt()

            if (existingConv == null ||
                conversationNeedsReset ||
                kvNearFull ||
                activeSessionConversationId != sessionId ||
                activeToolGroupsSignature != enabledToolGroups
            ) {
                runCatching { existingConv?.close() }
                val tools = toolkitRegistry.buildEnabledToolProviders(enabledToolGroups)
                // Strictly alternating user -> model pairs ending with model
                val seedMessages = mutableListOf<Message>()
                var expectUser = true
                for ((role, content) in recentHistory.takeLast(6)) {
                    val clean = content.trim().take(420)
                    if (clean.isBlank()) continue
                    val isUser = role == "user"
                    if (expectUser && isUser) {
                        seedMessages.add(Message.user(clean))
                        expectUser = false
                    } else if (!expectUser && !isUser) {
                        seedMessages.add(Message.model(clean))
                        expectUser = true
                    }
                }
                if (seedMessages.size % 2 != 0) {
                    seedMessages.removeAt(seedMessages.lastIndex)
                }
                val finalSeedMessages = seedMessages.takeLast(4)
                val convConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    initialMessages = finalSeedMessages,
                    tools = tools,
                    samplerConfig = SamplerConfig(
                        topK = profile.topK,
                        topP = profile.topP.toDouble(),
                        temperature = profile.temperature.toDouble()
                    ),
                    automaticToolCalling = true,
                    extraContext = mapOf("enable_thinking" to policy.allowThinking),
                    thinkingConfig = ThinkingConfig(
                        enableThinking = policy.allowThinking,
                        thinkingTokenBudget = policy.thinkingBudgetTokens
                    )
                )
                activeConversation = currentEngine.createConversation(convConfig)
                activeSessionConversationId = sessionId
                activeToolGroupsSignature = enabledToolGroups
                conversationNeedsReset = false
            }

            val conversation = activeConversation!!
            val startMs = System.currentTimeMillis()
            var firstTokenMs = 0L
            var tokenCounter = 0

            val parser = ThinkingStreamParser()
            val repetitionConfig = RepetitionPenaltyConfig(
                repetitionPenalty = 1.12f,
                presencePenalty = 0.25f,
                frequencyPenalty = 0.15f,
                windowSize = 96
            )
            val noRepeatConfig = NoRepeatNgramConfig(
                noRepeatNgramSize = 6,
                windowSize = 128
            )
            val turnExtraContext = mapOf("enable_thinking" to policy.allowThinking)
            val turnThinkingConfig = ThinkingConfig(
                enableThinking = policy.allowThinking,
                thinkingTokenBudget = policy.thinkingBudgetTokens
            )

            val messageFlow = if (!imageFilePath.isNullOrBlank() && File(imageFilePath).exists()) {
                val contentList = listOf(
                    Content.ImageFile(imageFilePath),
                    Content.Text(userPrompt.ifBlank { "Analizza questa immagine in dettaglio." })
                )
                conversation.sendMessageAsync(
                    Message.user(Contents.of(contentList)),
                    turnExtraContext,
                    repetitionConfig,
                    noRepeatConfig,
                    null,
                    policy.maxOutputTokens,
                    turnThinkingConfig
                )
            } else {
                conversation.sendMessageAsync(
                    userPrompt,
                    turnExtraContext,
                    repetitionConfig,
                    noRepeatConfig,
                    null,
                    policy.maxOutputTokens,
                    turnThinkingConfig
                )
            }

            var pacingDelayMs = policy.interChunkDelayMs
            var thermalNote = policy.statusNote
            var chunksSinceThermalRecheck = 0

            messageFlow.collect { chunkMsg ->
                val nativeThought = chunkMsg.channels.values.joinToString("")
                val contentText = chunkMsg.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                    .ifEmpty {
                        if (nativeThought.isEmpty() && chunkMsg.toolCalls.isEmpty()) {
                            chunkMsg.toString()
                        } else {
                            ""
                        }
                    }

                val (parsedThought, parsedVisible) = if (contentText.isNotEmpty()) {
                    parser.feed(contentText)
                } else {
                    "" to ""
                }

                val combinedThought = nativeThought + parsedThought
                val combinedVisible = parsedVisible

                if (combinedThought.isEmpty() && combinedVisible.isEmpty()) return@collect

                if (firstTokenMs == 0L) {
                    firstTokenMs = System.currentTimeMillis() - startMs
                }
                val chunkChars = combinedThought.length + combinedVisible.length
                tokenCounter += (chunkChars / 4).coerceAtLeast(1)
                val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L)) / 1000f
                val tps = tokenCounter / elapsedSec

                emit(
                    StreamChunk(
                        visibleDelta = combinedVisible,
                        thinkingDelta = combinedThought,
                        tokensPerSec = tps,
                        ttftMs = firstTokenMs,
                        thermalNote = thermalNote
                    )
                )

                // Periodically re-check thermal state during long responses (every ~40 chunks)
                chunksSinceThermalRecheck++
                if (chunksSinceThermalRecheck >= 40) {
                    chunksSinceThermalRecheck = 0
                    val liveSnap = ThermalAndMemoryGuard.readSnapshot(context)
                    val updatedPolicy = ThermalAndMemoryGuard.computePacingPolicy(
                        snapshot = liveSnap,
                        ecoModeEnabled = ecoThermalMode,
                        userRequestedThinking = policy.allowThinking
                    )
                    pacingDelayMs = updatedPolicy.interChunkDelayMs
                    thermalNote = updatedPolicy.statusNote
                    runCatching { Process.setThreadPriority(updatedPolicy.threadNicePriority) }
                }

                // Micro duty-cycle cooling break when Eco mode or warm thermal state requests it
                if (pacingDelayMs > 0L) {
                    delay(pacingDelayMs)
                }
            }

            val (tailThought, tailVisible) = parser.flush()
            if (tailThought.isNotEmpty() || tailVisible.isNotEmpty()) {
                val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L)) / 1000f
                emit(
                    StreamChunk(
                        visibleDelta = tailVisible,
                        thinkingDelta = tailThought,
                        tokensPerSec = tokenCounter / elapsedSec,
                        ttftMs = firstTokenMs,
                        thermalNote = thermalNote
                    )
                )
            }
        } catch (t: Throwable) {
            conversationNeedsReset = true
            throw t
        } finally {
            isTurnActive = false
            scheduleIdleKvTrim()
        }
    }.flowOn(engineDispatcher)

    private fun closeConversationInternal() {
        runCatching { activeConversation?.close() }
        activeConversation = null
        activeSessionConversationId = -1L
        activeToolGroupsSignature = emptySet()
        conversationNeedsReset = false
    }

    private fun unloadInternal(rememberForAutoReload: Boolean) {
        if (rememberForAutoReload && loadedProfile != null) {
            lastAutoUnloadedProfile = loadedProfile
        } else if (!rememberForAutoReload) {
            lastAutoUnloadedProfile = null
        }
        closeConversationInternal()
        runCatching { engine?.close() }
        engine = null
        loadedProfile = null
    }

    fun close() {
        runCatching {
            cancelHousekeepingTimers()
            unloadInternal(rememberForAutoReload = false)
            engineExecutor.shutdownNow()
        }
    }
}

private class ThinkingStreamParser {
    private var inThought = false
    private val buffer = StringBuilder()

    private val openTags = listOf("<thought>", "<|thought|>", "<|channel>thought\n", "<|channel>thought")
    private val closeTags = listOf("</thought>", "<|/thought|>", "<channel|>")

    fun feed(chunk: String): Pair<String, String> {
        buffer.append(chunk)
        val thinkOut = StringBuilder()
        val visOut = StringBuilder()

        while (buffer.isNotEmpty()) {
            val text = buffer.toString()
            if (!inThought) {
                val openIdx = openTags.mapNotNull { tag ->
                    val idx = text.indexOf(tag)
                    if (idx >= 0) idx to tag.length else null
                }.minByOrNull { it.first }

                if (openIdx != null) {
                    if (openIdx.first > 0) visOut.append(text.substring(0, openIdx.first))
                    buffer.delete(0, openIdx.first + openIdx.second)
                    inThought = true
                } else {
                    val safeEnd = (text.length - 18).coerceAtLeast(0)
                    val tail = text.substring(safeEnd)
                    if (tail.contains('<')) {
                        if (safeEnd > 0) {
                            visOut.append(text.substring(0, safeEnd))
                            buffer.delete(0, safeEnd)
                        }
                        break
                    } else {
                        visOut.append(text)
                        buffer.clear()
                    }
                }
            } else {
                val closeIdx = closeTags.mapNotNull { tag ->
                    val idx = text.indexOf(tag)
                    if (idx >= 0) idx to tag.length else null
                }.minByOrNull { it.first }

                if (closeIdx != null) {
                    if (closeIdx.first > 0) thinkOut.append(text.substring(0, closeIdx.first))
                    buffer.delete(0, closeIdx.first + closeIdx.second)
                    inThought = false
                } else {
                    val safeEnd = (text.length - 12).coerceAtLeast(0)
                    val tail = text.substring(safeEnd)
                    if (tail.contains('<')) {
                        if (safeEnd > 0) {
                            thinkOut.append(text.substring(0, safeEnd))
                            buffer.delete(0, safeEnd)
                        }
                        break
                    } else {
                        thinkOut.append(text)
                        buffer.clear()
                    }
                }
            }
        }
        return thinkOut.toString() to visOut.toString()
    }

    fun flush(): Pair<String, String> {
        if (buffer.isEmpty()) return "" to ""
        val rem = buffer.toString()
        buffer.clear()
        return if (inThought) rem to "" else "" to rem
    }
}
