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

    suspend fun loadModel(profile: ModelProfileEntity): Result<Unit> = withContext(engineDispatcher) {
        val file = File(profile.filePath)
        if (!file.exists()) {
            val err = "File modello non trovato: ${profile.filePath}"
            _status.value = EngineStatus.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        unloadInternal()

        val preferredGpu = profile.preferredBackend.equals("GPU", ignoreCase = true)
        val initialBackendName = if (preferredGpu) "GPU" else "CPU"
        _status.value = EngineStatus.Loading(profile.displayName, initialBackendName)

        val cacheDir = File(context.cacheDir, "litertlm_cache").apply { mkdirs() }.absolutePath
        val startMs = System.currentTimeMillis()

        val primaryAttempt = runCatching {
            val backend = if (preferredGpu) Backend.GPU() else Backend.CPU()
            val visionBackend = if (profile.supportsVision) {
                if (preferredGpu) Backend.GPU() else Backend.CPU()
            } else null

            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                visionBackend = visionBackend,
                maxNumTokens = profile.maxNumTokens.coerceIn(1024, 32768),
                cacheDir = cacheDir
            )
            Engine(config).also { it.initialize() }
        }

        val finalEngine = primaryAttempt.fold(
            onSuccess = {
                activeBackendLabel = initialBackendName
                it
            },
            onFailure = { gpuError ->
                if (!preferredGpu) {
                    val msg = "Impossibile inizializzare il modello su CPU: ${gpuError.message}"
                    _status.value = EngineStatus.Error(msg, gpuError.stackTraceToString().take(400))
                    return@withContext Result.failure(gpuError)
                }
                // Automatic safe fallback GPU -> CPU with diagnostic notice
                _status.value = EngineStatus.Loading(profile.displayName, "CPU (fallback)")
                runCatching {
                    val cpuConfig = EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        visionBackend = if (profile.supportsVision) Backend.CPU() else null,
                        maxNumTokens = profile.maxNumTokens.coerceIn(1024, 16384),
                        cacheDir = cacheDir
                    )
                    Engine(cpuConfig).also { it.initialize() }
                }.fold(
                    onSuccess = {
                        activeBackendLabel = "CPU (fallback da GPU)"
                        it
                    },
                    onFailure = { cpuError ->
                        val msg = "Caricamento fallito sia su GPU che su CPU: ${cpuError.message}"
                        _status.value = EngineStatus.Error(
                            msg,
                            "GPU: ${gpuError.message}\nCPU: ${cpuError.message}"
                        )
                        return@withContext Result.failure(cpuError)
                    }
                )
            }
        )

        engine = finalEngine
        loadedProfile = profile
        val elapsed = System.currentTimeMillis() - startMs
        _status.value = EngineStatus.Ready(
            model = profile,
            activeBackend = activeBackendLabel,
            visionEnabled = profile.supportsVision,
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
            conversation.sendMessageAsync(Message.of(contentList))
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
