package com.example.engine

import com.example.domain.model.ChatMessage
import com.example.domain.model.EngineTelemetry
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.InferenceConfig
import com.example.domain.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Architectural stub / pre-NDK harness for [LocalInferenceEngine].
 *
 * Does NOT make any network/API calls and does NOT bundle native C++ binaries yet.
 * Exercises the exact Kotlin Flow streaming pipeline, cooperative cancellation,
 * KV-cache telemetry, and GGUF config propagation so the front-end and state
 * layer can be verified before plugging in llama.cpp via JNI.
 */
class StubLlamaEngineAdapter : LocalInferenceEngine {

    private val engineMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    private val _engineState = MutableStateFlow(EngineLifecycleState.UNLOADED)
    override val engineState: StateFlow<EngineLifecycleState> = _engineState.asStateFlow()

    private val _loadedModel = MutableStateFlow<GgufModelSpec?>(null)
    override val loadedModel: StateFlow<GgufModelSpec?> = _loadedModel.asStateFlow()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    override val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    override val isNativeBridgeLinked: Boolean = false
    override val supportsGpuOffload: Boolean = false

    override suspend fun loadModel(model: GgufModelSpec, config: InferenceConfig): Result<Unit> {
        return engineMutex.withLock {
            try {
                _engineState.value = EngineLifecycleState.LOADING_MODEL
                // Simulates mmap + GGUF header validation time proportional to config
                delay(450L)
                _loadedModel.value = model
                val kvMb = config.estimateKvCacheMb(model)
                _telemetry.value = EngineTelemetry(
                    tokensPerSecond = 0f,
                    promptTokensUsed = 0,
                    generatedTokens = 0,
                    promptEvalMs = 0L,
                    estimatedKvCacheMb = kvMb,
                    estimatedTotalFootprintMb = model.estimatedRamMb + kvMb
                )
                _engineState.value = EngineLifecycleState.READY
                Result.success(Unit)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _engineState.value = EngineLifecycleState.ERROR
                Result.failure(t)
            }
        }
    }

    override suspend fun unloadModel() {
        cancelRequested.set(true)
        engineMutex.withLock {
            _loadedModel.value = null
            _telemetry.value = EngineTelemetry()
            _engineState.value = EngineLifecycleState.UNLOADED
        }
    }

    override fun streamCompletion(
        messages: List<ChatMessage>,
        config: InferenceConfig
    ): Flow<InferenceStreamEvent> = flow {
        val activeModel = _loadedModel.value
        if (activeModel == null) {
            emit(
                InferenceStreamEvent.Error(
                    message = "Nenhum modelo GGUF carregado no slot da engine.",
                    recoverable = true
                )
            )
            return@flow
        }

        cancelRequested.set(false)
        _engineState.value = EngineLifecycleState.GENERATING

        val formattedPrompt = buildString {
            if (config.systemPrompt.isNotBlank()) appendLine("system: ${config.systemPrompt}")
            messages.filter { it.content.isNotBlank() }.forEach { appendLine("${it.role.name.lowercase()}: ${it.content}") }
        }
        val promptTokens = PromptFormatter.estimateTokens(formattedPrompt)
        val kvMb = config.estimateKvCacheMb(activeModel)

        if (promptTokens >= config.contextLength) {
            _engineState.value = EngineLifecycleState.READY
            emit(
                InferenceStreamEvent.Error(
                    message = "Janela de contexto excedida ($promptTokens / ${config.contextLength} tokens).",
                    recoverable = true
                )
            )
            return@flow
        }

        // Simulated prompt evaluation phase (llama_decode batching)
        val evalDelayMs = (120L + (promptTokens * 2L / config.threads.coerceAtLeast(1))).coerceAtMost(900L)
        delay(evalDelayMs)

        if (cancelRequested.get()) {
            _engineState.value = EngineLifecycleState.READY
            emit(
                InferenceStreamEvent.Completed(
                    finishReason = FinishReason.STOPPED_BY_USER,
                    totalTokens = 0,
                    tokensPerSecond = 0f,
                    durationMs = evalDelayMs
                )
            )
            return@flow
        }

        emit(InferenceStreamEvent.Started(promptTokens = promptTokens, promptEvalTimeMs = evalDelayMs))

        val lastUserPrompt = messages.lastOrNull { it.role == MessageRole.USER }?.content?.trim().orEmpty()
        val diagnosticStreamText = buildArchitecturePreviewResponse(
            userPrompt = lastUserPrompt,
            model = activeModel,
            config = config,
            promptTokens = promptTokens
        )
        val tokenChunks = tokenizeForStreamPreview(diagnosticStreamText)

        val generationStartMs = System.currentTimeMillis()
        var emittedTokens = 0
        var finishReason = FinishReason.EOS_TOKEN

        try {
            for (chunk in tokenChunks) {
                if (cancelRequested.get()) {
                    finishReason = FinishReason.STOPPED_BY_USER
                    break
                }
                if (emittedTokens >= config.maxTokens) {
                    finishReason = FinishReason.MAX_TOKENS
                    break
                }
                if (promptTokens + emittedTokens >= config.contextLength) {
                    finishReason = FinishReason.CONTEXT_FULL
                    break
                }

                // Delay tuned slightly by threads to visualize streaming ergonomics
                val perTokenMs = (46L - (config.threads * 3L)).coerceIn(18L, 48L)
                delay(perTokenMs)

                emittedTokens++
                val elapsedSec = ((System.currentTimeMillis() - generationStartMs).coerceAtLeast(1L)) / 1000f
                val tps = ((emittedTokens / elapsedSec) * 10f).roundToInt() / 10f

                _telemetry.value = EngineTelemetry(
                    tokensPerSecond = tps,
                    promptTokensUsed = promptTokens + emittedTokens,
                    generatedTokens = emittedTokens,
                    promptEvalMs = evalDelayMs,
                    estimatedKvCacheMb = kvMb,
                    estimatedTotalFootprintMb = activeModel.estimatedRamMb + kvMb
                )

                emit(
                    InferenceStreamEvent.Token(
                        text = chunk,
                        totalTokens = emittedTokens,
                        tokensPerSecond = tps
                    )
                )
            }

            val totalDurationMs = (System.currentTimeMillis() - generationStartMs).coerceAtLeast(1L)
            val finalTps = ((emittedTokens / (totalDurationMs / 1000f)) * 10f).roundToInt() / 10f
            _engineState.value = EngineLifecycleState.READY
            emit(
                InferenceStreamEvent.Completed(
                    finishReason = finishReason,
                    totalTokens = emittedTokens,
                    tokensPerSecond = finalTps,
                    durationMs = totalDurationMs
                )
            )
        } catch (ce: CancellationException) {
            _engineState.value = EngineLifecycleState.READY
            throw ce
        } catch (t: Throwable) {
            _engineState.value = EngineLifecycleState.ERROR
            emit(InferenceStreamEvent.Error(message = t.message ?: "Falha no stream da engine"))
        }
    }.flowOn(Dispatchers.Default)

    override fun formatPromptPreview(messages: List<ChatMessage>, config: InferenceConfig): String =
        buildString {
            if (config.systemPrompt.isNotBlank()) appendLine("system: ${config.systemPrompt}")
            messages.filter { it.content.isNotBlank() }.forEach {
                appendLine("${it.role.name.lowercase()}: ${it.content}")
            }
        }

    override suspend fun stopGeneration() {
        cancelRequested.set(true)
    }

    private fun tokenizeForStreamPreview(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val regex = Regex("""\S+\s*|\n+""")
        regex.findAll(text).forEach { match ->
            tokens.add(match.value)
        }
        return tokens
    }

    private fun buildArchitecturePreviewResponse(
        userPrompt: String,
        model: GgufModelSpec,
        config: InferenceConfig,
        promptTokens: Int
    ): String {
        val promptPreview = if (userPrompt.length > 72) {
            userPrompt.take(72) + "…"
        } else {
            userPrompt
        }
        return buildString {
            appendLine("[Modo Arquitetura • Stub llama.cpp]")
            appendLine()
            appendLine("Pipeline de streaming ativo sem bibliotecas externas de rede. O prompt \"$promptPreview\" foi serializado no template Jinja embutido no GGUF ($promptTokens tokens estimados).")
            appendLine()
            appendLine("Configuração propagada para o contrato `LocalInferenceEngine`:")
            appendLine("```cpp")
            appendLine("// Parâmetros prontos para llama_context_params (JNI)")
            appendLine("model        = \"${model.fileName}\" (${model.quantization.label});")
            appendLine("n_ctx        = ${config.contextLength};")
            appendLine("n_threads    = ${config.threads};")
            appendLine("n_batch      = ${config.batchSize};")
            appendLine("n_gpu_layers = ${config.gpuLayers};")
            appendLine("use_mmap     = ${config.useMmap};")
            appendLine("use_mlock    = ${config.useMlock};")
            appendLine("kv_type      = ${config.kvCacheQuant.name};")
            appendLine("temp / top_p = ${config.temperature} / ${config.topP};")
            appendLine("```")
            append("Ao compilar a biblioteca nativa `libllama_jni.so` com o NDK, basta substituir `StubLlamaEngineAdapter` pela ponte C++ mantendo todo o front-end e o gerenciamento de estado intactos.")
        }
    }
}
