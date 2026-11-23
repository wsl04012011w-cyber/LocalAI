package com.example.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.example.data.AppDiagnosticsLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext

/**
 * JNI-backed inference adapter for llama.cpp.
 * SAF providers can expose pipes or non-seekable descriptors, so imported GGUF
 * files are staged as regular cache files for reliable random access/mmap.
 */
class JniLlamaEngineAdapter(
    private val context: Context
) : LocalInferenceEngine, Closeable {

    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val operationMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var activeGenerationId: Long = 0L


    private val _engineState = MutableStateFlow(EngineLifecycleState.UNLOADED)
    override val engineState: StateFlow<EngineLifecycleState> = _engineState.asStateFlow()

    private val _loadedModel = MutableStateFlow<GgufModelSpec?>(null)
    override val loadedModel: StateFlow<GgufModelSpec?> = _loadedModel.asStateFlow()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    override val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    override val isNativeBridgeLinked: Boolean = true

    init {
        AppDiagnosticsLogger.initialize(context)
    }

    override val supportsGpuOffload: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        nativeSupportsGpuOffload()
    }

    override suspend fun loadModel(
        model: GgufModelSpec,
        config: InferenceConfig
    ): Result<Unit> = operationMutex.withLock {
        requestActiveGenerationStop()
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

            val existingPrivateCopy = runCatching { privateModelFile(model) }
                .getOrNull()
                ?.takeIf(::hasValidGgufHeader)
            val modelUri = if (existingPrivateCopy == null) resolveModelUri(model) else null
            if (existingPrivateCopy == null && modelUri == null) {
                _engineState.value = EngineLifecycleState.ERROR
                AppDiagnosticsLogger.error(
                    "MODEL",
                    "Nenhuma cópia privada válida ou URI de origem disponível para ${model.fileName}."
                )
                return@withContext Result.failure(
                    IllegalStateException(
                        "GGUF não encontrado no armazenamento privado e a origem SAF/DownloadManager não está disponível. Importe o arquivo novamente."
                    )
                )
            }

            try {
                AppDiagnosticsLogger.info(
                    "MODEL",
                    "Iniciando carregamento: ${model.fileName}; id=${model.id}; tamanho=${model.fileSizeMb} MB; n_ctx=${config.contextLength}; mmap=${config.useMmap}; mlock=${config.useMlock}"
                )
                val privateModelFile = existingPrivateCopy ?: stageModelFile(modelUri!!, model)
                AppDiagnosticsLogger.info(
                    "MODEL",
                    "GGUF pronto no armazenamento privado: ${privateModelFile.absolutePath}; bytes=${privateModelFile.length()}"
                )
                val handle = nativeLoadModel(
                    privateModelFile.absolutePath,
                    config.gpuLayers,
                    config.useMmap,
                    config.useMlock,
                    AppDiagnosticsLogger.logFilePath(context)
                )
                check(handle != 0L) { "llama.cpp não retornou um handle de modelo válido." }

                nativeHandle = handle
                _loadedModel.value = model
                val kvCacheMb = config.estimateKvCacheMb(model)
                _telemetry.value = EngineTelemetry(
                    estimatedKvCacheMb = kvCacheMb,
                    estimatedTotalFootprintMb = model.estimatedRamMb + kvCacheMb
                )
                _engineState.value = EngineLifecycleState.READY
                AppDiagnosticsLogger.info(
                    "MODEL",
                    "Modelo confirmado como READY: ${model.fileName}; handle=$handle"
                )
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                AppDiagnosticsLogger.warn("MODEL", "Carregamento cancelado: ${model.fileName}")
                _engineState.value = EngineLifecycleState.ERROR
                throw cancelled
            } catch (failure: Throwable) {
                AppDiagnosticsLogger.error("MODEL", "Falha ao carregar ${model.fileName}", failure)
                _engineState.value = EngineLifecycleState.ERROR
                Result.failure(failure)
            }
        }
    }

    override suspend fun unloadModel() {
        requestActiveGenerationStop()
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

        val generationId = NEXT_GENERATION_ID.getAndIncrement()
        activeGenerationId = generationId
        val producer = this
        val generationFinished = AtomicBoolean(false)
        var promptTokenCount = 0

        AppDiagnosticsLogger.info(
            "INFERENCE",
            "Iniciando geração id=${generationId}; handle=${handle}; mensagens=${messages.size}; n_ctx=${config.contextLength}; n_batch=${config.batchSize}; n_threads=${config.threads}"
        )

        val generationJob = launch(nativeDispatcher) {
            _engineState.value = EngineLifecycleState.GENERATING
            try {
                val prompt = formatPromptWithModelTemplate(handle, messages, config)
                AppDiagnosticsLogger.info(
                    "INFERENCE",
                    "Prompt Jinja pronto para geração id=${generationId}; chars=${prompt.length}"
                )
                nativeGenerate(
                    handle = handle,
                    generationId = generationId,
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
                            AppDiagnosticsLogger.info(
                                "INFERENCE",
                                "Prefill concluído id=${generationId}; prompt_tokens=${promptTokens}; tempo=${promptEvalMs}ms"
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
                            if (totalTokens == 1) {
                                AppDiagnosticsLogger.info("INFERENCE", "Primeiro token recebido id=${generationId}")
                            }
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
                            AppDiagnosticsLogger.info(
                                "INFERENCE",
                                "Geração concluída id=${generationId}; finish=${finishReason}; tokens=${totalTokens}; duração=${durationMs}ms"
                            )
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
                            AppDiagnosticsLogger.error("INFERENCE", "Geração id=${generationId} falhou: ${message}")
                            _engineState.value = EngineLifecycleState.ERROR
                            producer.trySend(InferenceStreamEvent.Error(message))
                        }
                    }
                )

                if (_engineState.value == EngineLifecycleState.GENERATING) {
                    AppDiagnosticsLogger.warn(
                        "INFERENCE",
                        "nativeGenerate retornou sem callback de conclusão id=${generationId}."
                    )
                    _engineState.value = EngineLifecycleState.READY
                }
            } catch (cancelled: CancellationException) {
                AppDiagnosticsLogger.warn("INFERENCE", "Geração cancelada id=${generationId}.")
                _engineState.value = EngineLifecycleState.READY
                throw cancelled
            } catch (failure: Throwable) {
                AppDiagnosticsLogger.error(
                    "INFERENCE",
                    "Falha inesperada durante a geração id=${generationId}.",
                    failure
                )
                _engineState.value = EngineLifecycleState.ERROR
                producer.trySend(
                    InferenceStreamEvent.Error(
                        failure.message ?: "Falha inesperada na engine nativa."
                    )
                )
            } finally {
                generationFinished.set(true)
                if (activeGenerationId == generationId) activeGenerationId = 0L
                producer.close()
            }
        }

        awaitClose {
            // Closing the channel after onCompleted is normal. Only collector cancellation
            // before native completion should stop this generation.
            if (!generationFinished.get() && generationJob.isActive) {
                AppDiagnosticsLogger.warn(
                    "INFERENCE",
                    "Coletor cancelado durante geração id=${generationId}; solicitando parada nativa."
                )
                nativeRequestStop(generationId)
                generationJob.cancel()
            }
        }
    }

    override fun formatPromptPreview(messages: List<ChatMessage>, config: InferenceConfig): String {
        val handle = nativeHandle
        if (handle == 0L) return "Selecione e carregue um GGUF para renderizar o template Jinja do modelo."
        return runCatching { formatPromptWithModelTemplate(handle, messages, config) }
            .getOrElse { failure -> "Falha ao renderizar tokenizer.chat_template: ${failure.message ?: "template inválido"}" }
    }

    override suspend fun stopGeneration() {
        requestActiveGenerationStop()
    }

    override fun close() {
        requestActiveGenerationStop()
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

    private fun requestActiveGenerationStop() {
        val generationId = activeGenerationId
        if (generationId > 0L) {
            AppDiagnosticsLogger.warn("INFERENCE", "Solicitando interrupção da geração id=$generationId.")
            nativeRequestStop(generationId)
        }
    }

    private fun releaseModelInternal() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            nativeFreeModel(handle)
        }

        val modelName = _loadedModel.value?.fileName
        _loadedModel.value = null
        if (handle != 0L) {
            AppDiagnosticsLogger.info(
                "MODEL",
                "Handle nativo liberado; GGUF preservado em armazenamento privado: ${modelName ?: "modelo"}"
            )
        }
    }

    /**
     * Model copies live in filesDir (not cacheDir). Android may clear cached
     * files at any time, while llama.cpp requires a stable random-access mmap path.
     */
    private fun privateModelFile(model: GgufModelSpec): File {
        val directory = File(context.filesDir, "gguf-models")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Não foi possível criar o armazenamento privado dos modelos.")
        }
        val key = model.id.hashCode().toUInt().toString(16)
        return File(directory, "model_${key}.gguf")
    }

    private fun hasValidGgufHeader(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 4L) return@runCatching false
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
    }.getOrDefault(false)

    private suspend fun stageModelFile(uri: Uri, model: GgufModelSpec): File {
        val target = privateModelFile(model)
        if (hasValidGgufHeader(target)) {
            AppDiagnosticsLogger.info(
                "MODEL",
                "Reutilizando cópia privada existente: ${target.absolutePath}; bytes=${target.length()}"
            )
            return target
        }

        val partial = File(target.parentFile, "${target.name}.part")
        runCatching { partial.delete() }
        runCatching { target.delete() }
        AppDiagnosticsLogger.info(
            "MODEL",
            "Copiando GGUF do provedor ${uri.scheme ?: "unknown"} para filesDir/gguf-models."
        )
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("O provedor de arquivos não abriu o GGUF para leitura.")
            input.use { source ->
                FileOutputStream(partial).use { rawOutput ->
                    val output = BufferedOutputStream(rawOutput, 1024 * 1024)
                    source.buffered().use { bufferedSource ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = bufferedSource.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                    rawOutput.fd.sync()
                }
            }
            if (!hasValidGgufHeader(partial)) {
                throw IllegalStateException("O arquivo selecionado não contém um cabeçalho GGUF válido ou está incompleto.")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Não foi possível finalizar a cópia privada do modelo.")
            }
            AppDiagnosticsLogger.info(
                "MODEL",
                "Cópia privada concluída: ${target.absolutePath}; bytes=${target.length()}"
            )
            return target
        } catch (failure: Throwable) {
            runCatching { partial.delete() }
            AppDiagnosticsLogger.error("MODEL", "Falha ao copiar GGUF para o armazenamento privado", failure)
            if (failure is CancellationException) throw failure
            throw IllegalStateException(
                "Não foi possível copiar o GGUF para o armazenamento privado: ${failure.message ?: failure.javaClass.simpleName}",
                failure
            )
        }
    }

    override suspend fun removeStoredModel(model: GgufModelSpec) {
        operationMutex.withLock {
            withContext(nativeDispatcher) {
                if (_loadedModel.value?.id == model.id) {
                    throw IllegalStateException("Descarregue o modelo antes de remover sua cópia privada.")
                }
                val file = privateModelFile(model)
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("Não foi possível remover ${file.name} do armazenamento privado.")
                }
                AppDiagnosticsLogger.info("MODEL", "Cópia privada removida pelo usuário: ${file.name}")
            }
        }
    }

    private fun formatPromptWithModelTemplate(
        handle: Long,
        messages: List<ChatMessage>,
        config: InferenceConfig
    ): String {
        val roles = ArrayList<String>(messages.size + 1)
        val contents = ArrayList<String>(messages.size + 1)
        if (config.systemPrompt.isNotBlank()) {
            roles += "system"
            contents += config.systemPrompt.trim()
        }
        messages.filter { it.content.isNotBlank() }.forEach { message ->
            roles += when (message.role) {
                com.example.domain.model.MessageRole.USER -> "user"
                com.example.domain.model.MessageRole.ASSISTANT -> "assistant"
                com.example.domain.model.MessageRole.SYSTEM -> "system"
            }
            contents += message.content.trim()
        }
        return nativeFormatPrompt(handle, roles.toTypedArray(), contents.toTypedArray())
    }

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
        modelPath: String,
        gpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        diagnosticsFilePath: String
    ): Long

    private external fun nativeFormatPrompt(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>
    ): String

    private external fun nativeFreeModel(handle: Long)
    private external fun nativeRequestStop(generationId: Long)
    private external fun nativeSupportsGpuOffload(): Boolean

    private external fun nativeGenerate(
        handle: Long,
        generationId: Long,
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
        val NEXT_GENERATION_ID = AtomicLong(1L)

        const val FINISH_EOS = 0
        const val FINISH_MAX_TOKENS = 1
        const val FINISH_STOPPED = 2
        const val FINISH_CONTEXT_FULL = 3

        init {
            System.loadLibrary("llama_jni")
        }
    }
}
