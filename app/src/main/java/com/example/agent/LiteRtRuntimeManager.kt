package com.example.agent

import android.content.Context
import com.example.agent.tools.HermesToolkitRegistry
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    val ttftMs: Long = 0L
)

class LiteRtRuntimeManager(
    private val context: Context,
    private val toolkitRegistry: HermesToolkitRegistry
) {
    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hermes-litert-engine").apply { isDaemon = true }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var engine: Engine? = null
    private var activeConversation: Conversation? = null
    private var loadedProfile: ModelProfileEntity? = null
    private var activeBackendLabel: String = "GPU"
    private var activeSessionConversationId: Long = -1L
    private var activeToolGroupsSignature: Set<String> = emptySet()

    private data class LoadAttempt(
        val label: String,
        val backend: Backend,
        val visionBackend: Backend?,
        val maxNumTokens: Int?,
        val cacheDir: String?
    )

    suspend fun loadModel(profile: ModelProfileEntity): Result<Unit> = withContext(engineDispatcher) {
        val file = File(profile.filePath)
        if (!file.exists()) {
            val err = "File modello non trovato: ${profile.filePath}"
            _status.value = EngineStatus.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val lowerName = file.name.lowercase()
        val fileSizeMb = file.length() / (1024.0 * 1024.0)

        // Pre-flight check for browser-only WebGPU bundles or broken tiny downloads
        if (lowerName.contains("-web.") || lowerName.contains("_web.") || lowerName.endsWith("-web.litertlm") || lowerName.endsWith("-web.task")) {
            val msg = "File '${file.name}' è compilato per il browser (WebGPU), non per Android nativo."
            val detail = "Scarica da Hugging Face la variante Android standard 'gemma-4-E2B-it.litertlm' (GPU/CPU) oppure 'gemma-4-E2B-it_Google_Tensor_G5.litertlm' (NPU Pixel 10), NON il file '-web'."
            _status.value = EngineStatus.Error(msg, detail)
            return@withContext Result.failure(IllegalArgumentException("$msg $detail"))
        }
        if (file.length() < 10L * 1024L * 1024L) {
            val msg = "File '${file.name}' troppo piccolo (${String.format(java.util.Locale.US, "%.1f", fileSizeMb)} MB): download incompleto o file non valido."
            _status.value = EngineStatus.Error(msg, "Un modello Gemma 4 .litertlm pesa circa 2.5–3.7 GB.")
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        unloadInternal()

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

        val requestedCtx = profile.maxNumTokens.coerceIn(1024, 32768)
        val wantVision = profile.supportsVision

        val attempts = buildList {
            fun addNpuVariants() {
                if (!hasNativeLibDir) return
                val npuBackend = Backend.NPU(nativeLibraryDir = nativeLibDir)
                if (wantVision) {
                    add(LoadAttempt("NPU Tensor + Vision GPU", npuBackend, Backend.GPU(), requestedCtx, cacheDir))
                }
                add(LoadAttempt("NPU Tensor (solo testo)", npuBackend, null, requestedCtx, cacheDir))
                add(LoadAttempt("NPU Tensor (ctx default)", npuBackend, null, null, ":nocache"))
            }

            fun addGpuVariants() {
                if (wantVision) {
                    add(LoadAttempt("GPU + Vision GPU", Backend.GPU(), Backend.GPU(), requestedCtx, cacheDir))
                    add(LoadAttempt("GPU + Vision CPU", Backend.GPU(), Backend.CPU(), requestedCtx, cacheDir))
                }
                add(LoadAttempt("GPU (solo testo)", Backend.GPU(), null, requestedCtx, cacheDir))
                add(LoadAttempt("GPU (ctx default, no-cache)", Backend.GPU(), null, null, ":nocache"))
            }

            fun addCpuVariants() {
                if (wantVision) {
                    add(LoadAttempt("CPU + Vision CPU", Backend.CPU(), Backend.CPU(), requestedCtx.coerceAtMost(16384), cacheDir))
                }
                add(LoadAttempt("CPU (solo testo)", Backend.CPU(), null, requestedCtx.coerceAtMost(16384), cacheDir))
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
                    append("Causa 'Input tensor not found': questo .litertlm ha una firma tensori diversa dai backend disponibili (es. file '-web', compilato per un altro SoC come Qualcomm/MediaTek, oppure bundle MediaPipe legacy).\n")
                    append("Soluzione consigliata per Pixel 10 Pro XL: scarica da Hugging Face 'litert-community/gemma-4-E2B-it-litert-lm' il file 'gemma-4-E2B-it.litertlm' (GPU/CPU) oppure 'gemma-4-E2B-it_Google_Tensor_G5.litertlm' (NPU Tensor G5).\n")
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
        unloadInternal()
        _status.value = EngineStatus.Idle
    }

    suspend fun resetConversation() = withContext(engineDispatcher) {
        runCatching { activeConversation?.close() }
        activeConversation = null
        activeSessionConversationId = -1L
    }

    fun streamTurn(
        sessionId: Long,
        systemInstruction: String,
        userPrompt: String,
        imageFilePath: String?,
        enabledToolGroups: Set<String>
    ): Flow<StreamChunk> = flow {
        val currentEngine = engine
            ?: throw IllegalStateException("Nessun modello Gemma 4 caricato. Importa e carica un file .litertlm nella scheda Modello.")
        val profile = loadedProfile
            ?: throw IllegalStateException("Profilo modello non inizializzato.")

        // Recreate conversation if session changed or enabled tools changed
        if (activeConversation == null ||
            activeSessionConversationId != sessionId ||
            activeToolGroupsSignature != enabledToolGroups
        ) {
            runCatching { activeConversation?.close() }
            val tools = toolkitRegistry.buildEnabledToolProviders(enabledToolGroups)
            val convConfig = ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                tools = tools,
                samplerConfig = SamplerConfig(
                    topK = profile.topK,
                    topP = profile.topP.toDouble(),
                    temperature = profile.temperature.toDouble()
                ),
                automaticToolCalling = true
            )
            activeConversation = currentEngine.createConversation(convConfig)
            activeSessionConversationId = sessionId
            activeToolGroupsSignature = enabledToolGroups
        }

        val conversation = activeConversation!!
        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        var tokenCounter = 0

        val parser = ThinkingStreamParser()

        val messageFlow = if (!imageFilePath.isNullOrBlank() && File(imageFilePath).exists()) {
            val contentList = listOf(
                Content.ImageFile(imageFilePath),
                Content.Text(userPrompt.ifBlank { "Analizza questa immagine in dettaglio." })
            )
            conversation.sendMessageAsync(Message.user(Contents.of(contentList)))
        } else {
            conversation.sendMessageAsync(userPrompt)
        }

        messageFlow.collect { chunkMsg ->
            val rawText = chunkMsg.toString()
            if (rawText.isEmpty()) return@collect
            if (firstTokenMs == 0L) {
                firstTokenMs = System.currentTimeMillis() - startMs
            }
            tokenCounter += (rawText.length / 4).coerceAtLeast(1)
            val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L)) / 1000f
            val tps = tokenCounter / elapsedSec

            val (thinkingDelta, visibleDelta) = parser.feed(rawText)
            if (thinkingDelta.isNotEmpty() || visibleDelta.isNotEmpty()) {
                emit(
                    StreamChunk(
                        visibleDelta = visibleDelta,
                        thinkingDelta = thinkingDelta,
                        tokensPerSec = tps,
                        ttftMs = firstTokenMs
                    )
                )
            }
        }
    }.flowOn(engineDispatcher)

    private fun unloadInternal() {
        runCatching { activeConversation?.close() }
        activeConversation = null
        activeSessionConversationId = -1L
        runCatching { engine?.close() }
        engine = null
        loadedProfile = null
    }

    fun close() {
        runCatching {
            unloadInternal()
            engineExecutor.shutdownNow()
        }
    }
}

/**
 * Separates Gemma 4 thinking blocks (<thought>...</thought> or <|thought|>...<|/thought|>)
 * from visible assistant text while streaming chunks incrementally.
 */
private class ThinkingStreamParser {
    private var inThought = false
    private val buffer = StringBuilder()

    fun feed(chunk: String): Pair<String, String> {
        buffer.append(chunk)
        val thinkOut = StringBuilder()
        val visOut = StringBuilder()

        while (buffer.isNotEmpty()) {
            val text = buffer.toString()
            if (!inThought) {
                val openIdx = listOf("<thought>", "<|thought|>").mapNotNull { tag ->
                    val idx = text.indexOf(tag)
                    if (idx >= 0) idx to tag.length else null
                }.minByOrNull { it.first }

                if (openIdx != null) {
                    if (openIdx.first > 0) visOut.append(text.substring(0, openIdx.first))
                    buffer.delete(0, openIdx.first + openIdx.second)
                    inThought = true
                } else {
                    // Keep up to 11 trailing chars if they could be the start of a tag
                    val safeEnd = (text.length - 11).coerceAtLeast(0)
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
                val closeIdx = listOf("</thought>", "<|/thought|>").mapNotNull { tag ->
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
}
