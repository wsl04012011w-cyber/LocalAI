package com.example.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.domain.model.ChatMessage
import com.example.domain.model.EngineTelemetry
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.InferenceConfig
import com.example.domain.model.KvCacheQuant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * JNI-backed inference adapter for the llama.cpp v1.0.0 C API.
 *
 * The descriptor stays open while the model is loaded because llama.cpp may
 * memory-map /proc/self/fd/<fd> for the lifetime of the native model.
 */
class JniLlamaEngineAdapter(
    private val context: Context
) : LocalInferenceEngine, Closeable {

    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val operationMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var nativeHandle: Long = 0L

    private var modelDescriptor: ParcelFileDescriptor? = null

    private val _engineState = MutableStateFlow(EngineLifecycleState.UNLOADED)
    override val engineState: StateFlow<EngineLifecycleState> = _engineState.asStateFlow()

    private val _loadedModel = MutableStateFlow<GgufModelSpec?>(null)
    override val loadedModel: StateFlow<GgufModelSpec?> = _loadedModel.asStateFlow()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    override val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    override val isNativeBridgeLinked: Boolean = true

    override val supportsGpuOffload: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        nativeSupportsGpuOffload()
    }

    override suspend fun loadModel(
        model: GgufModelSpec,
        config: InferenceConfig
    ): Result<Unit> = operationMutex.withLock {
        nativeRequestStop()
        withContext(nativeDispatcher) {
            _engineState.value = EngineLifecycleState.LOADING_MODEL
            releaseModelInternal()

            if (config.gpuLayers > 0 && !supportsGpuOffload) {
                _engineState.value = EngineLifecycleState.ERROR
                return@withContext Result.failure(
                    IllegalStateException(
                        "Esta build não inclui backend de GPU. Defina n_gpu_layers como 0."
                    )
                )
            }

            val modelUri = resolveModelUri(model)
            if (modelUri == null) {
                _engineState.value = EngineLifecycleState.ERROR
                return@withContext Result.failure(
                    IllegalStateException(
                        "Arquivo GGUF não encontrado. Baixe o modelo e selecione o arquivo em Modelos > Abrir."
                    )
                )
            }

            var openedDescriptor: ParcelFileDescriptor? = null
            try {
                openedDescriptor = context.contentResolver.openFileDescriptor(modelUri, "r")
                    ?: throw IllegalStateException("Não foi possível abrir o arquivo GGUF selecionado.")

                val fdPath = "/proc/self/fd/${openedDescriptor.fd}"
                val handle = nativeLoadModel(
                    fdPath,
                    config.gpuLayers,
                    config.useMmap,
                    config.useMlock
                )
                check(handle != 0L) { "llama.cpp não retornou um handle de modelo válido." }

                nativeHandle = handle
                modelDescriptor = openedDescriptor
                openedDescriptor = null

                _loadedModel.value = model
                val kvCacheMb = config.estimateKvCacheMb(model)
                _telemetry.value = EngineTelemetry(
                    estimatedKvCacheMb = kvCacheMb,
                    estimatedTotalFootprintMb = model.estimatedRamMb + kvCacheMb
                )
                _engineState.value = EngineLifecycleState.READY
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                runCatching { openedDescriptor?.close() }
                _engineState.value = EngineLifecycleState.ERROR
                throw cancelled
            } catch (failure: Throwable) {
                runCatching { openedDescriptor?.close() }
                _engineState.value = EngineLifecycleState.ERROR
                Result.failure(failure)
            }
        }
    }

    override suspend fun unloadModel() {
        nativeRequestStop()
        operationMutex.withLock {
            withContext(nativeDispatcher) {
                releaseModelInternal()
                _telemetry.value = EngineTelemetry()
                _engineState.value = EngineLifecycleState.UNLOADED
            }
        }
    }

    override fun streamCompletion(
        messages: List<ChatMessage>,
        config: InferenceConfig
    ): Flow<InferenceStreamEvent> = channelFlow {
        val handle = nativeHandle
        if (handle == 0L) {
            send(
                InferenceStreamEvent.Error(
                    "Nenhum modelo GGUF foi carregado. Selecione um arquivo local em Modelos.",
                    recoverable = true
                )
            )
            return@channelFlow
        }

        val producer = this
        val prompt = PromptFormatter.formatPrompt(messages, config)
        val generationJob = launch(nativeDispatcher) {
            // Reset only after this generation reaches the serialized native queue.
            nativeResetStop()
            var promptTokenCount = 0
            _engineState.value = EngineLifecycleState.GENERATING
            try {
                nativeGenerate(
                    handle = handle,
                    prompt = prompt,
                    contextLength = config.contextLength,
                    batchSize = config.batchSize,
                    threads = config.threads,
                    kvCacheType = config.kvCacheQuant.toNativeType(),
                    maxTokens = config.maxTokens,
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK,
                    repeatPenalty = config.repeatPenalty,
                    callbacks = object : NativeCallbacks {
                        override fun onStarted(promptTokens: Int, promptEvalMs: Long) {
                            promptTokenCount = promptTokens
                            _telemetry.value = _telemetry.value.copy(
                                promptTokensUsed = promptTokens,
                                generatedTokens = 0,
                                tokensPerSecond = 0f,
                                promptEvalMs = promptEvalMs
                            )
                            producer.trySend(
                                InferenceStreamEvent.Started(
                                    promptTokens = promptTokens,
                                    promptEvalTimeMs = promptEvalMs
                                )
                            )
                        }

                        override fun onToken(
                            text: String,
                            totalTokens: Int,
                            tokensPerSecond: Float
                        ) {
                            _telemetry.value = _telemetry.value.copy(
                                promptTokensUsed = promptTokenCount + totalTokens,
                                generatedTokens = totalTokens,
                                tokensPerSecond = tokensPerSecond
                            )
                            producer.trySend(
                                InferenceStreamEvent.Token(
                                    text = text,
                                    totalTokens = totalTokens,
                                    tokensPerSecond = tokensPerSecond
                                )
                            )
                        }

                        override fun onCompleted(
                            finishReason: Int,
                            totalTokens: Int,
                            tokensPerSecond: Float,
                            durationMs: Long
                        ) {
                            _engineState.value = EngineLifecycleState.READY
                            producer.trySend(
                                InferenceStreamEvent.Completed(
                                    finishReason = finishReason.toFinishReason(),
                                    totalTokens = totalTokens,
                                    tokensPerSecond = tokensPerSecond,
                                    durationMs = durationMs
                                )
                            )
                        }

                        override fun onError(message: String) {
                            _engineState.value = EngineLifecycleState.ERROR
                            producer.trySend(InferenceStreamEvent.Error(message))
                        }
                    }
                )

                if (_engineState.value == EngineLifecycleState.GENERATING) {
                    _engineState.value = EngineLifecycleState.READY
                }
            } catch (cancelled: CancellationException) {
                _engineState.value = EngineLifecycleState.READY
                throw cancelled
            } catch (failure: Throwable) {
                _engineState.value = EngineLifecycleState.ERROR
                producer.trySend(
                    InferenceStreamEvent.Error(
                        failure.message ?: "Falha inesperada na engine nativa."
                    )
                )
            } finally {
                producer.close()
            }
        }

        awaitClose {
            if (generationJob.isActive) {
                nativeRequestStop()
                generationJob.cancel()
            }
        }
    }

    override suspend fun stopGeneration() {
        nativeRequestStop()
    }

    override fun close() {
        nativeRequestStop()
        cleanupScope.launch {
            runCatching {
                operationMutex.withLock {
                    withContext(nativeDispatcher) {
                        releaseModelInternal()
                        _telemetry.value = EngineTelemetry()
                        _engineState.value = EngineLifecycleState.UNLOADED
                    }
                }
            }
        }
    }

    private fun releaseModelInternal() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            nativeFreeModel(handle)
        }

        val descriptor = modelDescriptor
        modelDescriptor = null
        runCatching { descriptor?.close() }
        _loadedModel.value = null
    }

    /**
     * Imported models use their SAF Uri. Catalog models use the content Uri
     * from a completed Android DownloadManager task.
     */
    private fun resolveModelUri(model: GgufModelSpec): Uri? {
        model.uriString?.let { value ->
            return runCatching { Uri.parse(value) }.getOrNull()
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        val cursor = manager.query(
            DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        ) ?: return null

        cursor.use {
            val titleColumn = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val idColumn = it.getColumnIndex(DownloadManager.COLUMN_ID)
            if (titleColumn < 0 || idColumn < 0) return null

            while (it.moveToNext()) {
                if (it.getString(titleColumn) == model.fileName) {
                    return manager.getUriForDownloadedFile(it.getLong(idColumn))
                }
            }
        }
        return null
    }

    private fun KvCacheQuant.toNativeType(): Int = when (this) {
        KvCacheQuant.F16 -> 1
        KvCacheQuant.Q8_0 -> 8
        KvCacheQuant.Q4_0 -> 2
    }

    private fun Int.toFinishReason(): FinishReason = when (this) {
        FINISH_EOS -> FinishReason.EOS_TOKEN
        FINISH_MAX_TOKENS -> FinishReason.MAX_TOKENS
        FINISH_STOPPED -> FinishReason.STOPPED_BY_USER
        FINISH_CONTEXT_FULL -> FinishReason.CONTEXT_FULL
        else -> FinishReason.EOS_TOKEN
    }

    private interface NativeCallbacks {
        fun onStarted(promptTokens: Int, promptEvalMs: Long)
        fun onToken(text: String, totalTokens: Int, tokensPerSecond: Float)
        fun onCompleted(
            finishReason: Int,
            totalTokens: Int,
            tokensPerSecond: Float,
            durationMs: Long
        )
        fun onError(message: String)
    }

    private external fun nativeLoadModel(
        fileDescriptorPath: String,
        gpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean
    ): Long

    private external fun nativeFreeModel(handle: Long)
    private external fun nativeRequestStop()
    private external fun nativeResetStop()
    private external fun nativeSupportsGpuOffload(): Boolean

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        contextLength: Int,
        batchSize: Int,
        threads: Int,
        kvCacheType: Int,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callbacks: NativeCallbacks
    )

    private companion object {
        const val FINISH_EOS = 0
        const val FINISH_MAX_TOKENS = 1
        const val FINISH_STOPPED = 2
        const val FINISH_CONTEXT_FULL = 3

        init {
            System.loadLibrary("llama_jni")
        }
    }
}
