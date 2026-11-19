package com.example.agent

import android.content.Context
import android.os.Build
import android.os.Process
import com.example.agent.tools.MobHarnToolkitRegistry
import com.example.agent.tools.ParsedToolCall
import com.example.agent.tools.ToolCallRecoveryParser
import com.example.data.db.ModelProfileEntity
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
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
    val thermalNote: String? = null,
    val contextTokensUsed: Int = 0,
    val contextTokensMax: Int = 0
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
    private var activeMaxContextTokens: Int = 8192
    @Volatile private var conversationNeedsReset: Boolean = false
    @Volatile private var isTurnActive: Boolean = false
    @Volatile var isAppInBackground: Boolean = false
        private set

    private var idleKvTrimJob: Job? = null
    private var backgroundUnloadJob: Job? = null

    fun getActiveContextStats(): Pair<Int, Int> {
        val conv = activeConversation
        val used = if (conv != null && runCatching { conv.isAlive }.getOrDefault(false)) {
            runCatching { conv.getTokenCount() }.getOrDefault(0)
        } else {
            0
        }
        return used to activeMaxContextTokens
    }

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
        val hasTensorNpuLib = !nativeLibDir.isNullOrBlank() &&
            File(nativeLibDir, "libLiteRtDispatch_GoogleTensor.so").exists()

        val looksLikeTensorNpuFile = lowerName.contains("tensor") ||
            lowerName.contains("_g5") ||
            lowerName.contains("_g6") ||
            lowerName.contains("npu") ||
            lowerName.contains("edgetpu")
        val looksLikeOtherVendorNpu = lowerName.contains("qualcomm") ||
            lowerName.contains("qnn") ||
            lowerName.contains("mediatek") ||
            lowerName.contains("mtk")

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""
        val socLower = "${socModel} ${Build.HARDWARE} ${Build.MODEL}".lowercase()
        val isSupportedTensorG5Plus = socLower.contains("tensor g5") ||
            socLower.contains("tensor g6") ||
            socLower.contains("pixel 10") ||
            socLower.contains("pixel 11") ||
            socLower.contains("laguna")

        val requestedNpu = profile.preferredBackend.equals("NPU", ignoreCase = true)

        // Pre-flight safety guard: calling Backend.NPU() on a non-AOT .litertlm or an unsupported SoC
        // triggers an uncatchable native SIGABRT or kernel DMA OOM freeze inside libLiteRtDispatch_GoogleTensor.so.
        if (requestedNpu || looksLikeTensorNpuFile || looksLikeOtherVendorNpu) {
            val blockReason: Pair<String, String>? = when {
                looksLikeOtherVendorNpu ->
                    "File compilato per NPU Qualcomm/MediaTek, incompatibile con il runtime Google Tensor." to
                        "Questo APK include il runtime NPU Google Tensor. Scarica un file '_Google_Tensor_G5.litertlm' (su Pixel 10+) oppure la variante standard GPU/CPU 'gemma-4-E2B-it.litertlm'."
                requestedNpu && !looksLikeTensorNpuFile ->
                    "Questo file '${file.name}' è la variante GPU/CPU standard, non precompilata AOT per la NPU." to
                        "La NPU Google Tensor richiede un modello compilato AOT (es. 'gemma-4-E2B-it_Google_Tensor_G5.litertlm'). Forzare un file standard sulla NPU causa crash nativi o blocco del telefono.\n\nSoluzione: seleziona il backend GPU (o CPU) nelle opzioni Tune ▾ del modello."
                requestedNpu && !isSupportedTensorG5Plus ->
                    "NPU LiteRT-LM non supportata su questo chip (${socModel.ifBlank { Build.HARDWARE }})." to
                        "Il runtime NPU Google Tensor di LiteRT-LM supporta solo Google Tensor G5+ (serie Pixel 10 o successivi). Su Tensor G1–G4 o altri SoC usa il backend GPU."
                requestedNpu && !hasTensorNpuLib ->
                    "Libreria nativa NPU (libLiteRtDispatch_GoogleTensor.so) non trovata." to
                        "Usa il backend GPU oppure verifica che l'APK sia stato compilato con useLegacyPackaging = true."
                else -> null
            }

            if (blockReason != null) {
                _status.value = EngineStatus.Error(blockReason.first, blockReason.second)
                return@withContext Result.failure(IllegalStateException(blockReason.first))
            }
        }

        // Check available RAM before invoking NPU DMA allocations to prevent full-device freeze
        val ramSnap = runCatching { ThermalAndMemoryGuard.readSnapshot(context) }.getOrNull()
        val canSafelyUseNpu = hasTensorNpuLib &&
            looksLikeTensorNpuFile &&
            isSupportedTensorG5Plus &&
            (ramSnap == null || ramSnap.systemFreeRamGb >= 2.2)

        if (requestedNpu && !canSafelyUseNpu) {
            val freeRam = ramSnap?.systemFreeRamGb ?: 0.0
            val msg = "RAM libera insufficiente per la NPU (${String.format(java.util.Locale.US, "%.2f", freeRam)} GB)."
            val detail = "L'inizializzazione AOT sulla NPU richiede almeno 2.2 GB di RAM libera per evitare blocchi di sistema. Chiudi le app in background o carica il modello su GPU."
            _status.value = EngineStatus.Error(msg, detail)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val pref = when {
            requestedNpu -> "NPU"
            profile.preferredBackend.equals("CPU", ignoreCase = true) -> "CPU"
            looksLikeTensorNpuFile && canSafelyUseNpu -> "NPU"
            else -> "GPU"
        }

        // Lean mobile-first KV-cache ceiling: pre-allocating 16K–32K KV tensors burns 1.2–2.4 GB of unified RAM.
        // Long-horizon conversations are handled by our 3-tier rolling checkpoints + BM25 memory vault,
        // keeping native GPU KV-cache compact (4K–6K in Eco mode, up to 16K only with Eco OFF & high free RAM).
        val freeRamGb = ramSnap?.systemFreeRamGb ?: 5.0
        val ramAwareCap = if (ecoThermalMode) {
            if (freeRamGb < 3.5) 4096 else 6144
        } else {
            when {
                freeRamGb >= 7.0 -> 16384
                freeRamGb >= 4.5 -> 8192
                else -> 6144
            }
        }
        val requestedCtx = profile.maxNumTokens.coerceIn(2048, ramAwareCap)
        val wantVision = profile.supportsVision

        val attempts = buildList {
            fun addNpuVariants() {
                if (!canSafelyUseNpu || nativeLibDir.isNullOrBlank()) return
                val npuBackend = Backend.NPU(nativeLibraryDir = nativeLibDir)
                // AOT NPU graphs have static precompiled shapes: always try clean text-only default ctx first
                // to avoid DMA/ION memory spikes that freeze Android, and never hammer the NPU driver repeatedly.
                add(LoadAttempt("NPU Tensor (AOT)", npuBackend, null, null, ":nocache"))
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
                }
                else -> {
                    // Never fall back into Backend.NPU on standard GPU/CPU models
                    addGpuVariants()
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
            var candidate: Engine? = null
            val result = runCatching {
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = attempt.backend,
                    visionBackend = attempt.visionBackend,
                    maxNumTokens = attempt.maxNumTokens,
                    cacheDir = attempt.cacheDir
                )
                candidate = Engine(config)
                candidate!!.initialize()
                candidate!!
            }
            if (result.isSuccess) {
                initializedEngine = result.getOrThrow()
                winningAttempt = attempt
                break
            } else {
                // Always release native handles immediately so retries never stack gigabytes in RAM
                runCatching { candidate?.close() }
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
        activeMaxContextTokens = (winningAttempt.maxNumTokens ?: requestedCtx).coerceIn(2048, 32768)
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

    fun markBackgroundStateImmediate(inBackground: Boolean) {
        isAppInBackground = inBackground
    }

    fun onAppBackgrounded(autoUnloadEnabled: Boolean) {
        isAppInBackground = true
        // Release Conversation KV-cache quickly (8s) in background when idle to free hundreds of MB of RAM
        idleKvTrimJob?.cancel()
        idleKvTrimJob = housekeepingScope.launch {
            delay(8_000L)
            if (!isTurnActive) {
                trimConversationCacheAsync()
            }
        }
        // Release full model weights in background to keep overall system RAM low
        backgroundUnloadJob?.cancel()
        if (autoUnloadEnabled && engine != null) {
            val snap = runCatching { ThermalAndMemoryGuard.readSnapshot(context) }.getOrNull()
            val unloadDelayMs = if (snap?.isBatteryConstrained == true) 30_000L else 60_000L
            backgroundUnloadJob = housekeepingScope.launch {
                delay(unloadDelayMs)
                if (!isTurnActive) {
                    autoUnloadForMemoryAsync()
                }
            }
        }
    }

    fun onAppForegrounded() {
        isAppInBackground = false
        backgroundUnloadJob?.cancel()
        backgroundUnloadJob = null
    }

    private fun scheduleIdleKvTrim() {
        idleKvTrimJob?.cancel()
        idleKvTrimJob = housekeepingScope.launch {
            // Trim idle KV-cache after 45s in foreground (10s in background) to keep RAM lean;
            // next turn re-seeds instantly from the 3-tier Hot Window + Session Checkpoint.
            delay(if (isAppInBackground) 10_000L else 45_000L)
            if (!isTurnActive) {
                trimConversationCacheAsync()
            }
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
        ecoThermalMode: Boolean = true,
        perMessageCharLimit: Int = 760,
        maxSeedMessages: Int = 8,
        forceConversationReset: Boolean = false
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

        // Immediately drop to lowest CPU nice priority if running in background so user's foreground app never lags
        val initialNice = if (isAppInBackground) Process.THREAD_PRIORITY_LOWEST else policy.threadNicePriority
        runCatching { Process.setThreadPriority(initialNice) }

        idleKvTrimJob?.cancel()
        backgroundUnloadJob?.cancel()
        toolkitRegistry.monitor.beginTurn()
        isTurnActive = true

        try {
            // Check if existing KV cache is near saturation (>70% of active context) or dirty
            val existingConv = activeConversation
            val tokenLimit = activeMaxContextTokens.coerceIn(2048, 16384)
            val currentTokens = if (existingConv != null && runCatching { existingConv.isAlive }.getOrDefault(false)) {
                runCatching { existingConv.getTokenCount() }.getOrDefault(0)
            } else {
                Int.MAX_VALUE
            }
            val kvNearFull = currentTokens >= (tokenLimit * 0.70f).toInt()

            // Sticky Session Tool Groups: reuse warm KV-cache when active tools already cover this turn
            // and avoid dragging heavy multi-group tool schemas onto plain zero-tool chat turns.
            val warmToolsSatisfyTurn = activeSessionConversationId == sessionId &&
                enabledToolGroups.all { it in activeToolGroupsSignature } &&
                (enabledToolGroups.isNotEmpty() || activeToolGroupsSignature.size <= 1)

            val needsNewConversation = existingConv == null ||
                forceConversationReset ||
                conversationNeedsReset ||
                kvNearFull ||
                activeSessionConversationId != sessionId ||
                !warmToolsSatisfyTurn

            val useToolsOnPrimary = enabledToolGroups.isNotEmpty()
            // When thinking is enabled, rely on Gemma 4's native `enable_thinking` channel control
            // (thinkingConfig = null) instead of a tight thinkingTokenBudget that can slice CoT mid-sentence
            // without closing `<channel|>`. When thinking is disabled, suppress it explicitly.
            val effectiveThinkingConfig = if (policy.allowThinking) {
                null
            } else {
                ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
            }

            if (needsNewConversation) {
                runCatching { existingConv?.close() }
                // Cap sticky union to 2 groups so tool schemas stay compact and KV-cache RAM remains lean
                val groupsToBind = if (activeSessionConversationId == sessionId && !forceConversationReset && !kvNearFull && enabledToolGroups.isNotEmpty()) {
                    val union = activeToolGroupsSignature + enabledToolGroups
                    if (union.size <= 2) union else enabledToolGroups
                } else {
                    enabledToolGroups
                }
                val tools = toolkitRegistry.buildEnabledToolProviders(groupsToBind)

                // Strictly alternating user -> model pairs ending with model, using head+tail compression
                val seedMessages = mutableListOf<Message>()
                var expectUser = true
                val cappedHistory = recentHistory.takeLast((maxSeedMessages + 2).coerceAtMost(12))
                for ((role, content) in cappedHistory) {
                    val clean = ContextAndMemoryEngine.compactSingleMessage(content, perMessageCharLimit)
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
                val finalSeedMessages = seedMessages.takeLast(maxSeedMessages.coerceIn(2, 10))
                val effectiveTemp = if (groupsToBind.isNotEmpty()) {
                    profile.temperature.toDouble().coerceAtMost(0.65)
                } else {
                    profile.temperature.toDouble()
                }
                val convConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    initialMessages = finalSeedMessages,
                    tools = tools,
                    samplerConfig = SamplerConfig(
                        topK = profile.topK,
                        topP = profile.topP.toDouble(),
                        temperature = effectiveTemp
                    ),
                    automaticToolCalling = tools.isNotEmpty(),
                    extraContext = mapOf("enable_thinking" to policy.allowThinking),
                    thinkingConfig = effectiveThinkingConfig
                )
                activeConversation = currentEngine.createConversation(convConfig)
                activeSessionConversationId = sessionId
                activeToolGroupsSignature = groupsToBind
                conversationNeedsReset = false
            }

            val conversation = activeConversation!!
            val startMs = System.currentTimeMillis()
            var firstTokenMs = 0L
            var tokenCounter = 0
            var totalVisibleChars = 0
            val capturedThought = StringBuilder()
            var lastSampledKvTokens = runCatching { conversation.getTokenCount() }.getOrDefault(0)

            val parser = ThinkingStreamParser()
            // CRITICAL: Never apply NoRepeatNgramConfig or RepetitionPenaltyConfig on Gemma 4!
            // Hard n-gram banning masks exact dates/numbers/names from the system prompt or tool output
            // (e.g. corrupting "19 novembre 2026" into "17 novembre 2026" or "Cardito" into "Carditoito"),
            // and repetition penalties break Gemma 4's repeated `<|"|>` tool delimiters.
            val effectiveMaxOutputTokens = if (useToolsOnPrimary || policy.allowThinking) {
                maxOf(policy.maxOutputTokens, 1024)
            } else {
                policy.maxOutputTokens
            }
            val turnExtraContext = mapOf("enable_thinking" to policy.allowThinking)

            var pacingDelayMs = policy.interChunkDelayMs
            var currentFgNice = policy.threadNicePriority
            var lastAppliedBackgroundState = isAppInBackground
            var thermalNote = policy.statusNote
            var chunksSinceThermalRecheck = 0
            var chunksSinceKvSample = 0

            suspend fun collectMessageStream(
                flowToCollect: Flow<Message>,
                streamParser: ThinkingStreamParser
            ) {
                flowToCollect.collect { chunkMsg ->
                    // Dynamically react the instant the user switches to another app (or comes back)
                    val bgNow = isAppInBackground
                    if (bgNow != lastAppliedBackgroundState) {
                        lastAppliedBackgroundState = bgNow
                        val targetNice = if (bgNow) Process.THREAD_PRIORITY_LOWEST else currentFgNice
                        runCatching { Process.setThreadPriority(targetNice) }
                    }

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
                        streamParser.feed(contentText)
                    } else {
                        "" to ""
                    }

                    val combinedThought = nativeThought + parsedThought
                    val combinedVisible = parsedVisible

                    if (combinedThought.isEmpty() && combinedVisible.isEmpty()) return@collect

                    if (combinedThought.isNotEmpty() && capturedThought.length < 1500) {
                        capturedThought.append(combinedThought)
                    }
                    if (combinedVisible.isNotBlank()) {
                        totalVisibleChars += combinedVisible.trim().length
                    }
                    if (firstTokenMs == 0L) {
                        firstTokenMs = System.currentTimeMillis() - startMs
                    }
                    val chunkChars = combinedThought.length + combinedVisible.length
                    tokenCounter += (chunkChars / 4).coerceAtLeast(1)
                    val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L)) / 1000f
                    val tps = tokenCounter / elapsedSec

                    chunksSinceKvSample++
                    if (chunksSinceKvSample >= 20) {
                        chunksSinceKvSample = 0
                        activeConversation?.let { conv ->
                            if (runCatching { conv.isAlive }.getOrDefault(false)) {
                                lastSampledKvTokens = runCatching { conv.getTokenCount() }.getOrDefault(lastSampledKvTokens)
                            }
                        }
                    }

                    val liveNote = if (bgNow) {
                        "Esecuzione gentile in background (priorità minima)"
                    } else {
                        thermalNote
                    }

                    emit(
                        StreamChunk(
                            visibleDelta = combinedVisible,
                            thinkingDelta = combinedThought,
                            tokensPerSec = tps,
                            ttftMs = firstTokenMs,
                            thermalNote = liveNote,
                            contextTokensUsed = lastSampledKvTokens + tokenCounter,
                            contextTokensMax = tokenLimit
                        )
                    )

                    chunksSinceThermalRecheck++
                    if (chunksSinceThermalRecheck >= 36) {
                        chunksSinceThermalRecheck = 0
                        val liveSnap = ThermalAndMemoryGuard.readSnapshot(context)
                        val updatedPolicy = ThermalAndMemoryGuard.computePacingPolicy(
                            snapshot = liveSnap,
                            ecoModeEnabled = ecoThermalMode,
                            userRequestedThinking = policy.allowThinking
                        )
                        pacingDelayMs = updatedPolicy.interChunkDelayMs
                        currentFgNice = updatedPolicy.threadNicePriority
                        thermalNote = updatedPolicy.statusNote
                        val targetNice = if (isAppInBackground) Process.THREAD_PRIORITY_LOWEST else currentFgNice
                        runCatching { Process.setThreadPriority(targetNice) }
                    }

                    // Cooperative GPU/CPU yield in background: gives Android SurfaceFlinger & foreground apps
                    // clean frame slots so the phone stays buttery smooth while MobHarn works in background.
                    val effectiveDelayMs = if (isAppInBackground) {
                        maxOf(pacingDelayMs, 32L)
                    } else {
                        pacingDelayMs
                    }
                    if (effectiveDelayMs > 0L) {
                        delay(effectiveDelayMs)
                    }
                }

                val (tailThought, tailVisible) = streamParser.flush()
                val finalKv = activeConversation?.let { conv ->
                    if (runCatching { conv.isAlive }.getOrDefault(false)) {
                        runCatching { conv.getTokenCount() }.getOrDefault(lastSampledKvTokens)
                    } else lastSampledKvTokens
                } ?: lastSampledKvTokens

                if (tailThought.isNotEmpty() || tailVisible.isNotEmpty()) {
                    if (tailThought.isNotEmpty() && capturedThought.length < 1500) {
                        capturedThought.append(tailThought)
                    }
                    if (tailVisible.isNotBlank()) {
                        totalVisibleChars += tailVisible.trim().length
                    }
                    val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L)) / 1000f
                    emit(
                        StreamChunk(
                            visibleDelta = tailVisible,
                            thinkingDelta = tailThought,
                            tokensPerSec = tokenCounter / elapsedSec,
                            ttftMs = firstTokenMs,
                            thermalNote = thermalNote,
                            contextTokensUsed = finalKv,
                            contextTokensMax = tokenLimit
                        )
                    )
                }
            }

            var recoveredToolCalls: List<ParsedToolCall> = emptyList()
            var parseFailureFallback = false

            try {
                val messageFlow = if (!imageFilePath.isNullOrBlank() && File(imageFilePath).exists()) {
                    val contentList = listOf(
                        Content.ImageFile(imageFilePath),
                        Content.Text(userPrompt.ifBlank { "Analizza questa immagine in dettaglio." })
                    )
                    conversation.sendMessageAsync(
                        Message.user(Contents.of(contentList)),
                        turnExtraContext,
                        null,
                        null,
                        null,
                        effectiveMaxOutputTokens,
                        effectiveThinkingConfig
                    )
                } else {
                    conversation.sendMessageAsync(
                        userPrompt,
                        turnExtraContext,
                        null,
                        null,
                        null,
                        effectiveMaxOutputTokens,
                        effectiveThinkingConfig
                    )
                }
                collectMessageStream(messageFlow, parser)

                // Check if raw <|tool_call>...<tool_call|> blocks leaked into text without throwing
                val leakedBlocks = parser.drainCapturedToolBlocks()
                if (leakedBlocks.isNotEmpty()) {
                    recoveredToolCalls = leakedBlocks.flatMap { ToolCallRecoveryParser.extractToolCallsFromText(it) }
                }
            } catch (t: Throwable) {
                if (ToolCallRecoveryParser.isToolParseFailure(t)) {
                    val fullErr = generateSequence(t) { it.cause }
                        .mapNotNull { it.message }
                        .joinToString("\n")
                    recoveredToolCalls = ToolCallRecoveryParser.extractToolCallsFromError(fullErr)
                    parseFailureFallback = true
                    // Native C++ conversation state is tainted after an FC parse error; close it cleanly
                    closeConversationInternal()
                } else {
                    throw t
                }
            }

            // Collect tool outputs either from recovered tool calls OR from native tool calls whose
            // turn exhausted its output/CoT budget before emitting any visible reply.
            val executedOutputs: List<Pair<String, String>> = if (recoveredToolCalls.isNotEmpty()) {
                val ran = withContext(Dispatchers.IO) {
                    recoveredToolCalls.take(3).mapNotNull { call ->
                        val output = toolkitRegistry.invokeToolByName(call.name, call.args)
                        if (output != null) call.name to output else null
                    }
                }
                closeConversationInternal()
                ran
            } else if (!parseFailureFallback && totalVisibleChars == 0 && toolkitRegistry.monitor.getTurnOutputs().isNotEmpty()) {
                // Native automatic tool calling ran tools successfully, but the model spent all tokens in <thought>
                // without producing visible text — run a fast, clean no-thinking synthesis pass!
                closeConversationInternal()
                toolkitRegistry.monitor.getTurnOutputs().takeLast(3)
            } else {
                emptyList()
            }

            val noThinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
            val noThinkingExtra = mapOf("enable_thinking" to false)

            if (executedOutputs.isNotEmpty()) {
                val synthesisSystem = buildString {
                    appendLine("Sei MobHarn, un assistente AI personale in italiano.")
                    appendLine("Gli strumenti richiesti sono GIA' STATI ESEGUITI qui sotto.")
                    appendLine("Rispondi all'utente in modo chiaro, diretto e conciso basandoti SOLO sui risultati reali ottenuti. Se i risultati non contengono il dato esatto, dillo onestamente. Non scrivere chiamate tool.")
                }.trim()
                val synthesisPrompt = buildString {
                    appendLine("Richiesta dell'utente:")
                    appendLine(userPrompt)
                    appendLine()
                    appendLine("Risultati reali ottenuti:")
                    executedOutputs.forEach { (toolName, output) ->
                        appendLine("[$toolName]:")
                        appendLine(output.take(1400))
                        appendLine()
                    }
                    append("Risposta finale diretta in italiano:")
                }

                val synthConvConfig = ConversationConfig(
                    systemInstruction = Contents.of(synthesisSystem),
                    initialMessages = emptyList(),
                    tools = emptyList(),
                    samplerConfig = SamplerConfig(
                        topK = profile.topK,
                        topP = profile.topP.toDouble(),
                        temperature = profile.temperature.toDouble().coerceAtMost(0.55)
                    ),
                    automaticToolCalling = false,
                    extraContext = noThinkingExtra,
                    thinkingConfig = noThinkingConfig
                )
                val synthConv = currentEngine.createConversation(synthConvConfig)
                try {
                    val synthParser = ThinkingStreamParser()
                    val synthFlow = synthConv.sendMessageAsync(
                        synthesisPrompt,
                        noThinkingExtra,
                        null,
                        null,
                        null,
                        policy.maxOutputTokens,
                        noThinkingConfig
                    )
                    collectMessageStream(synthFlow, synthParser)
                } finally {
                    runCatching { synthConv.close() }
                    conversationNeedsReset = true
                }
            } else if (parseFailureFallback || totalVisibleChars == 0) {
                // Universal empty-reply / parse-error safety net: if the model stopped inside CoT or
                // produced zero visible characters, immediately run a crisp, no-thinking, no-tools pass
                // with the real system context (including live date/time & memories) so the user NEVER sees "(risposta vuota)".
                closeConversationInternal()
                val recoverySystem = systemInstruction.take(1800)
                val recoverySeeds = mutableListOf<Message>()
                var expUser = true
                for ((role, content) in recentHistory.takeLast(4)) {
                    val clean = ContextAndMemoryEngine.compactSingleMessage(content, 400)
                    if (clean.isBlank()) continue
                    if (expUser && role == "user") {
                        recoverySeeds.add(Message.user(clean))
                        expUser = false
                    } else if (!expUser && role != "user") {
                        recoverySeeds.add(Message.model(clean))
                        expUser = true
                    }
                }
                if (recoverySeeds.size % 2 != 0) recoverySeeds.removeAt(recoverySeeds.lastIndex)

                val fallbackConvConfig = ConversationConfig(
                    systemInstruction = Contents.of(recoverySystem),
                    initialMessages = recoverySeeds,
                    tools = emptyList(),
                    samplerConfig = SamplerConfig(
                        topK = profile.topK,
                        topP = profile.topP.toDouble(),
                        temperature = profile.temperature.toDouble().coerceAtMost(0.55)
                    ),
                    automaticToolCalling = false,
                    extraContext = noThinkingExtra,
                    thinkingConfig = noThinkingConfig
                )
                val fallbackConv = currentEngine.createConversation(fallbackConvConfig)
                try {
                    val fallbackParser = ThinkingStreamParser()
                    val fallbackFlow = fallbackConv.sendMessageAsync(
                        userPrompt,
                        noThinkingExtra,
                        null,
                        null,
                        null,
                        policy.maxOutputTokens,
                        noThinkingConfig
                    )
                    collectMessageStream(fallbackFlow, fallbackParser)
                } finally {
                    runCatching { fallbackConv.close() }
                    conversationNeedsReset = true
                }
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
    private var inToolCall = false
    private val buffer = StringBuilder()
    private val currentToolBuffer = StringBuilder()
    private val capturedToolBlocks = mutableListOf<String>()

    private val openThoughtTags = listOf("<thought>", "<|thought|>", "<|channel>thought\n", "<|channel>thought")
    private val closeThoughtTags = listOf("</thought>", "<|/thought|>", "<channel|>")
    private val openToolTag = "<|tool_call>"
    private val closeToolTag = "<tool_call|>"

    fun drainCapturedToolBlocks(): List<String> {
        val copy = capturedToolBlocks.toList()
        capturedToolBlocks.clear()
        return copy
    }

    fun feed(chunk: String): Pair<String, String> {
        buffer.append(chunk)
        val thinkOut = StringBuilder()
        val visOut = StringBuilder()

        while (buffer.isNotEmpty()) {
            val text = buffer.toString()
            when {
                inToolCall -> {
                    val closeIdx = text.indexOf(closeToolTag)
                    if (closeIdx >= 0) {
                        currentToolBuffer.append(text.substring(0, closeIdx))
                        capturedToolBlocks.add("$openToolTag${currentToolBuffer}$closeToolTag")
                        currentToolBuffer.clear()
                        buffer.delete(0, closeIdx + closeToolTag.length)
                        inToolCall = false
                    } else {
                        val safeEnd = (text.length - closeToolTag.length).coerceAtLeast(0)
                        if (safeEnd > 0) {
                            currentToolBuffer.append(text.substring(0, safeEnd))
                            buffer.delete(0, safeEnd)
                        }
                        break
                    }
                }
                !inThought -> {
                    val thoughtMatch = openThoughtTags.mapNotNull { tag ->
                        val idx = text.indexOf(tag)
                        if (idx >= 0) idx to tag.length else null
                    }.minByOrNull { it.first }
                    val toolIdx = text.indexOf(openToolTag)

                    val nextIsTool = toolIdx >= 0 && (thoughtMatch == null || toolIdx < thoughtMatch.first)
                    when {
                        nextIsTool -> {
                            if (toolIdx > 0) visOut.append(text.substring(0, toolIdx))
                            buffer.delete(0, toolIdx + openToolTag.length)
                            currentToolBuffer.clear()
                            inToolCall = true
                        }
                        thoughtMatch != null -> {
                            if (thoughtMatch.first > 0) visOut.append(text.substring(0, thoughtMatch.first))
                            buffer.delete(0, thoughtMatch.first + thoughtMatch.second)
                            inThought = true
                        }
                        else -> {
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
                    }
                }
                else -> {
                    val closeIdx = closeThoughtTags.mapNotNull { tag ->
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
        }
        return thinkOut.toString() to visOut.toString()
    }

    fun flush(): Pair<String, String> {
        if (inToolCall) {
            currentToolBuffer.append(buffer)
            buffer.clear()
            if (currentToolBuffer.isNotBlank()) {
                capturedToolBlocks.add("$openToolTag${currentToolBuffer}$closeToolTag")
            }
            currentToolBuffer.clear()
            inToolCall = false
            return "" to ""
        }
        if (buffer.isEmpty()) return "" to ""
        val rem = buffer.toString()
        buffer.clear()
        return if (inThought) rem to "" else "" to rem
    }
}
