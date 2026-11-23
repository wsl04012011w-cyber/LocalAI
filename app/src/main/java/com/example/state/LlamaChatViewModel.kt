package com.example.state

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDiagnosticsLogger
import com.example.data.DeviceHardwareInspector
import com.example.data.GgufRepository
import com.example.data.ModelDownloadDispatcher
import com.example.domain.model.ChatMessage
import com.example.domain.model.DeviceMemoryInfo
import com.example.domain.model.EngineLimits
import com.example.domain.model.EngineTelemetry
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.InferenceConfig
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.engine.EngineLifecycleState
import com.example.engine.FinishReason
import com.example.engine.InferenceStreamEvent
import com.example.engine.JniLlamaEngineAdapter
import com.example.engine.LocalInferenceEngine
import com.example.engine.PromptFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.Closeable
import java.util.UUID

class LlamaChatViewModel(application: Application) : AndroidViewModel(application) {

    private val hardwareInspector = DeviceHardwareInspector(application.applicationContext)
    private val downloadDispatcher = ModelDownloadDispatcher(application.applicationContext)
    private val initialMemory = hardwareInspector.inspectMemoryAndCpu()
    private val repository = GgufRepository(application.applicationContext, defaultThreads = (initialMemory.cpuCores / 2).coerceIn(2, 6))
    private val engine: LocalInferenceEngine = JniLlamaEngineAdapter(application.applicationContext)

    private val currentDestination = MutableStateFlow(AppDestination.CHAT)
    private val composerDraft = MutableStateFlow("")
    private val isPromptInspectorOpen = MutableStateFlow(false)
    private val statusBannerMessage = MutableStateFlow<String?>(null)
    private val deviceMemory = MutableStateFlow(initialMemory)

    private var generationJob: Job? = null
    private var modelLoadInProgress: Boolean = false

    init {
        AppDiagnosticsLogger.initialize(application.applicationContext)
        AppDiagnosticsLogger.info(
            "APP",
            "LlamaChatViewModel criado; ABI=${initialMemory.primaryAbi}; RAM total=${initialMemory.totalRamMb} MB"
        )
    }

    private data class CoreEngineSnapshot(
        val sessions: List<com.example.domain.model.ConversationSession>,
        val activeSessionId: String,
        val models: List<GgufModelSpec>,
        val loadedModel: GgufModelSpec?,
        val engineState: EngineLifecycleState,
        val config: InferenceConfig,
        val telemetry: EngineTelemetry
    )

    private val coreFlow = combine(
        repository.sessions,
        repository.activeSessionId,
        repository.models,
        engine.loadedModel,
        engine.engineState
    ) { sessions, activeId, models, loaded, state ->
        listOf(sessions, activeId, models, loaded, state)
    }.combine(
        combine(repository.config, engine.telemetry) { config, telemetry -> config to telemetry }
    ) { base, (config, telemetry) ->
        @Suppress("UNCHECKED_CAST")
        CoreEngineSnapshot(
            sessions = base[0] as List<com.example.domain.model.ConversationSession>,
            activeSessionId = base[1] as String,
            models = base[2] as List<GgufModelSpec>,
            loadedModel = base[3] as GgufModelSpec?,
            engineState = base[4] as EngineLifecycleState,
            config = config,
            telemetry = telemetry
        )
    }

    private val uiOverlayFlow = combine(
        currentDestination,
        composerDraft,
        isPromptInspectorOpen,
        statusBannerMessage,
        deviceMemory
    ) { dest, draft, inspector, banner, mem ->
        UiOverlaySnapshot(dest, draft, inspector, banner, mem)
    }

    private data class UiOverlaySnapshot(
        val destination: AppDestination,
        val draft: String,
        val inspectorOpen: Boolean,
        val banner: String?,
        val memory: DeviceMemoryInfo
    )

    val uiState: StateFlow<LlamaChatUiState> = combine(coreFlow, uiOverlayFlow) { core, ui ->
        LlamaChatUiState(
            currentDestination = ui.destination,
            sessions = core.sessions,
            activeSessionId = core.activeSessionId,
            models = core.models,
            loadedModel = core.loadedModel,
            engineState = core.engineState,
            isNativeBridgeLinked = engine.isNativeBridgeLinked,
            gpuOffloadAvailable = engine.supportsGpuOffload,
            config = core.config,
            telemetry = core.telemetry,
            deviceMemory = ui.memory,
            composerDraft = ui.draft,
            isPromptInspectorOpen = ui.inspectorOpen,
            statusBannerMessage = ui.banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LlamaChatUiState(
            deviceMemory = initialMemory,
            config = repository.config.value
        )
    )

    fun onAction(action: LlamaChatAction) {
        when (action) {
            is LlamaChatAction.SelectDestination -> currentDestination.value = action.destination
            is LlamaChatAction.UpdateDraft -> composerDraft.value = action.text
            is LlamaChatAction.SendPrompt -> handleSendPrompt(action.overrideText)
            LlamaChatAction.StopGeneration -> handleStopGeneration()
            LlamaChatAction.RegenerateLastResponse -> handleRegenerateLast()

            is LlamaChatAction.LoadModel -> handleLoadModel(action.model)
            LlamaChatAction.LoadRecommendedModel -> {
                val recommended = repository.models.value.firstOrNull()
                if (recommended != null) handleLoadModel(recommended)
            }
            LlamaChatAction.UnloadModel -> handleUnloadModel()
            is LlamaChatAction.DownloadModel -> handleDownloadModel(action.model)
            is LlamaChatAction.OpenModelRepository -> handleOpenRepo(action.url)
            is LlamaChatAction.ImportGgufUri -> handleImportGgufUri(action.uri)
            is LlamaChatAction.RemoveImportedModel -> handleRemoveImportedModel(action.modelId)
            LlamaChatAction.RefreshDeviceMemory -> {
                deviceMemory.value = hardwareInspector.inspectMemoryAndCpu()
            }

            LlamaChatAction.CreateNewSession -> {
                if (!uiState.value.isGenerating) {
                    repository.createNewSession(engine.loadedModel.value?.id)
                    currentDestination.value = AppDestination.CHAT
                }
            }
            is LlamaChatAction.SelectSession -> {
                if (!uiState.value.isGenerating) {
                    repository.selectSession(action.sessionId)
                    currentDestination.value = AppDestination.CHAT
                }
            }
            is LlamaChatAction.DeleteSession -> {
                if (!uiState.value.isGenerating) {
                    repository.deleteSession(action.sessionId)
                }
            }

            is LlamaChatAction.SetPromptInspectorOpen -> isPromptInspectorOpen.value = action.open
            LlamaChatAction.DismissStatusBanner -> statusBannerMessage.value = null
            LlamaChatAction.ExportDiagnostics -> handleExportDiagnostics()

            is LlamaChatAction.UpdateContextLength -> repository.updateConfig { current ->
                val maxModelCtx = engine.loadedModel.value?.maxContextLength ?: 8192
                val boundedCtx = action.nCtx.coerceIn(512, maxModelCtx)
                current.copy(
                    contextLength = boundedCtx,
                    batchSize = current.batchSize.coerceAtMost(boundedCtx),
                    maxTokens = current.maxTokens.coerceAtMost(boundedCtx)
                )
            }
            is LlamaChatAction.UpdateThreads -> repository.updateConfig { current ->
                val maxCores = deviceMemory.value.cpuCores.coerceAtLeast(EngineLimits.MIN_THREADS)
                current.copy(threads = action.threads.coerceIn(EngineLimits.MIN_THREADS, maxCores))
            }
            is LlamaChatAction.UpdateBatchSize -> repository.updateConfig { current ->
                current.copy(batchSize = action.nBatch.coerceIn(64, current.contextLength))
            }
            is LlamaChatAction.UpdateGpuLayers -> {
                if (action.layers > 0 && !engine.supportsGpuOffload) {
                    statusBannerMessage.value = "Esta versão foi compilada apenas com CPU; offload GPU ainda não está disponível."
                } else {
                    repository.updateConfig { current ->
                        current.copy(gpuLayers = action.layers.coerceIn(0, EngineLimits.MAX_GPU_LAYERS))
                    }
                }
            }
            is LlamaChatAction.ToggleMmap -> repository.updateConfig { it.copy(useMmap = action.enabled) }
            is LlamaChatAction.ToggleMlock -> repository.updateConfig { it.copy(useMlock = action.enabled) }
            is LlamaChatAction.UpdateKvCacheQuant -> repository.updateConfig { it.copy(kvCacheQuant = action.quant) }
            is LlamaChatAction.UpdateTemperature -> repository.updateConfig {
                it.copy(temperature = action.temperature.coerceIn(EngineLimits.MIN_TEMP, EngineLimits.MAX_TEMP))
            }
            is LlamaChatAction.UpdateTopP -> repository.updateConfig {
                it.copy(topP = action.topP.coerceIn(0.10f, 1.00f))
            }
            is LlamaChatAction.UpdateTopK -> repository.updateConfig {
                it.copy(topK = action.topK.coerceIn(1, 100))
            }
            is LlamaChatAction.UpdateRepeatPenalty -> repository.updateConfig {
                it.copy(repeatPenalty = action.penalty.coerceIn(1.00f, 1.50f))
            }
            is LlamaChatAction.UpdateMaxTokens -> repository.updateConfig { current ->
                current.copy(maxTokens = action.maxTokens.coerceIn(128, current.contextLength))
            }
            is LlamaChatAction.UpdateSystemPrompt -> repository.updateConfig { it.copy(systemPrompt = action.prompt) }
            LlamaChatAction.ResetDefaultConfig -> {
                val safeThreads = (deviceMemory.value.cpuCores / 2).coerceIn(2, 6)
                repository.updateConfig { InferenceConfig(threads = safeThreads) }
                statusBannerMessage.value = "Parâmetros restaurados dentro dos limites do dispositivo."
            }
        }
    }

    fun buildFormattedPromptPreview(): String {
        val state = uiState.value
        val messages = state.activeSession?.messages.orEmpty()
        return engine.formatPromptPreview(messages, state.config)
    }

    private fun handleDownloadModel(model: GgufModelSpec) {
        when (val result = downloadDispatcher.enqueueModelDownload(model)) {
            is ModelDownloadDispatcher.DispatchResult.EnqueuedToDownloads -> {
                statusBannerMessage.value = "Download iniciado para Downloads/${result.fileName}"
            }
            is ModelDownloadDispatcher.DispatchResult.OpenedInBrowser -> {
                statusBannerMessage.value = "Link GGUF aberto no navegador para download."
            }
            is ModelDownloadDispatcher.DispatchResult.Failed -> {
                statusBannerMessage.value = result.reason
            }
        }
    }

    private fun handleOpenRepo(url: String) {
        if (url.isBlank()) return
        when (val result = downloadDispatcher.openUrlExternally(url)) {
            is ModelDownloadDispatcher.DispatchResult.Failed -> statusBannerMessage.value = result.reason
            else -> Unit
        }
    }

    private fun handleLoadModel(model: GgufModelSpec) {
        if (uiState.value.isGenerating) {
            statusBannerMessage.value = "Interrompa o streaming antes de trocar o perfil GGUF."
            return
        }
        if (engine.loadedModel.value?.id == model.id &&
            engine.engineState.value == EngineLifecycleState.READY
        ) {
            AppDiagnosticsLogger.info("MODEL", "Modelo já está ativo; pedido duplicado ignorado: ${model.fileName}")
            statusBannerMessage.value = "${model.name} já está carregado e pronto."
            return
        }
        if (modelLoadInProgress || uiState.value.isLoadingModel) {
            AppDiagnosticsLogger.warn("MODEL", "Toque repetido ignorado: outro modelo já está sendo carregado.")
            statusBannerMessage.value = "Um modelo já está sendo carregado. Aguarde a conclusão."
            return
        }
        modelLoadInProgress = true
        viewModelScope.launch {
            try {
                AppDiagnosticsLogger.info("MODEL", "Solicitação de carregamento recebida: ${model.fileName}")
                repository.updateConfig { current ->
                    val boundedCtx = current.contextLength.coerceAtMost(model.maxContextLength)
                    current.copy(
                        contextLength = boundedCtx,
                        batchSize = current.batchSize.coerceAtMost(boundedCtx),
                        maxTokens = current.maxTokens.coerceAtMost(boundedCtx)
                    )
                }
                val result = runCatching { engine.loadModel(model, repository.config.value) }
                    .getOrElse { Result.failure(it) }
                val isActuallyReady = result.isSuccess &&
                    engine.loadedModel.value?.id == model.id &&
                    engine.engineState.value == EngineLifecycleState.READY
                if (isActuallyReady) {
                    AppDiagnosticsLogger.info("MODEL", "Estado confirmado: ${model.fileName} continua ativo e engine está READY.")
                    statusBannerMessage.value = "${model.name} carregado e pronto para uso."
                } else {
                    val failure = result.exceptionOrNull()
                    AppDiagnosticsLogger.error(
                        "MODEL",
                        "Carregamento não terminou com modelo ativo; estado=${engine.engineState.value}; loaded=${engine.loadedModel.value?.id}",
                        failure
                    )
                    statusBannerMessage.value =
                        "Falha ao carregar ${model.name}: ${failure?.message ?: "a engine não confirmou o modelo ativo"}"
                }
            } finally {
                modelLoadInProgress = false
            }
        }
    }

    private fun handleUnloadModel() {
        viewModelScope.launch {
            AppDiagnosticsLogger.info("MODEL", "Descarregamento solicitado pelo usuário.")
            generationJob?.cancel()
            engine.unloadModel()
            statusBannerMessage.value = "Modelo descarregado da memória; cópia privada preservada."
        }
    }

    private fun handleImportGgufUri(uri: Uri) {
        AppDiagnosticsLogger.info("IMPORT", "Usuário selecionou um arquivo GGUF pelo Storage Access Framework.")
        val permissionPersisted = runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess

        val parsed = runCatching { hardwareInspector.inspectPickedGgufUri(uri) }
            .onFailure { AppDiagnosticsLogger.error("IMPORT", "Falha ao ler metadados do GGUF escolhido", it) }
            .getOrNull()
        if (parsed != null) {
            repository.registerPickedModel(parsed)
            AppDiagnosticsLogger.info(
                "IMPORT",
                "GGUF identificado: ${parsed.fileName}; ${parsed.fileSizeMb} MB; permissão persistida=$permissionPersisted"
            )
            statusBannerMessage.value =
                "Preparando cópia privada de ${parsed.fileName} (${parsed.fileSizeMb} MB)."
            handleLoadModel(parsed)
        } else {
            AppDiagnosticsLogger.error("IMPORT", "Não foi possível identificar o arquivo GGUF selecionado.")
            statusBannerMessage.value = "Não foi possível ler os metadados do arquivo GGUF selecionado."
        }
    }

    private fun handleRemoveImportedModel(modelId: String) {
        val model = repository.models.value.firstOrNull { it.id == modelId } ?: return
        viewModelScope.launch {
            try {
                if (engine.loadedModel.value?.id == modelId) {
                    generationJob?.cancel()
                    engine.unloadModel()
                }
                engine.removeStoredModel(model)
                repository.removePickedModel(modelId)
                statusBannerMessage.value = "Modelo removido do app e da lista."
            } catch (failure: Throwable) {
                AppDiagnosticsLogger.error("MODEL", "Falha ao remover modelo importado", failure)
                statusBannerMessage.value = "Falha ao remover modelo: ${failure.message}"
            }
        }
    }

    private fun handleSendPrompt(overrideText: String?) {
        val promptText = (overrideText ?: composerDraft.value).trim()
        if (promptText.isBlank() || uiState.value.isGenerating || uiState.value.isLoadingModel) return

        val sessionId = uiState.value.activeSession?.id ?: return
        if (overrideText == null) {
            composerDraft.value = ""
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = promptText,
            status = MessageStatus.COMPLETED,
            tokenCount = PromptFormatter.estimateTokens(promptText)
        )

        repository.updateSessionMessages(
            sessionId = sessionId,
            autoTitleFromFirstPrompt = promptText
        ) { current ->
            current + userMessage
        }

        if (engine.loadedModel.value == null) {
            val defaultModel = repository.models.value.firstOrNull()
            if (defaultModel != null) {
                viewModelScope.launch {
                    val res = engine.loadModel(defaultModel, repository.config.value)
                    if (res.isSuccess) {
                        startStreamingResponse(sessionId)
                    } else {
                        statusBannerMessage.value = "Selecione um perfil GGUF na aba Modelos."
                    }
                }
                return
            }
        }

        startStreamingResponse(sessionId)
    }

    private fun handleRegenerateLast() {
        if (uiState.value.isGenerating || uiState.value.isLoadingModel) return
        val session = uiState.value.activeSession ?: return
        val lastUserIndex = session.messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex == -1) return

        repository.updateSessionMessages(session.id) { messages ->
            messages.take(lastUserIndex + 1)
        }
        startStreamingResponse(session.id)
    }

    private fun startStreamingResponse(sessionId: String) {
        if (generationJob?.isActive == true) {
            AppDiagnosticsLogger.warn(
                "INFERENCE",
                "Nova solicitação ignorada porque a geração anterior ainda está encerrando."
            )
            statusBannerMessage.value = "A geração anterior ainda está encerrando. Tente novamente em instantes."
            return
        }
        val sessionSnapshot = repository.sessions.value.firstOrNull { it.id == sessionId } ?: return
        val historyForPrompt = sessionSnapshot.messages
        val assistantMsgId = UUID.randomUUID().toString()
        val modelName = engine.loadedModel.value?.name ?: "GGUF Slot"

        val placeholderAssistant = ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.QUEUED,
            modelName = modelName
        )

        repository.updateSessionMessages(sessionId) { it + placeholderAssistant }

        AppDiagnosticsLogger.info("INFERENCE", "Criada resposta id=$assistantMsgId; histórico=${historyForPrompt.size} mensagens; modelo=${engine.loadedModel.value?.fileName}")
        generationJob = viewModelScope.launch {
            val responseBuffer = StringBuilder()
            val thinkingBuffer = StringBuilder()
            val thinkingParser = ThinkingTagParser()

            suspend fun appendSmoothly(text: String, isThinking: Boolean, totalTokens: Int, tokensPerSecond: Float) {
                var index = 0
                val target = if (isThinking) thinkingBuffer else responseBuffer
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    val count = Character.charCount(codePoint)
                    target.append(text.substring(index, index + count))
                    index += count
                    repository.updateSessionMessages(sessionId) { messages ->
                        messages.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    content = responseBuffer.toString(),
                                    thinkingContent = thinkingBuffer.toString(),
                                    status = MessageStatus.STREAMING,
                                    tokenCount = if (totalTokens >= 0) totalTokens else msg.tokenCount,
                                    tokensPerSecond = if (tokensPerSecond >= 0f) tokensPerSecond else msg.tokensPerSecond
                                )
                            } else msg
                        }
                    }
                    delay(STREAM_CHARACTER_DELAY_MS)
                }
            }

            suspend fun appendParsed(text: String, totalTokens: Int, tokensPerSecond: Float) {
                thinkingParser.push(text).forEach { segment ->
                    appendSmoothly(segment.text, segment.isThinking, totalTokens, tokensPerSecond)
                }
            }

            try {
                engine.streamCompletion(historyForPrompt, repository.config.value).collect { event ->
                    when (event) {
                        is InferenceStreamEvent.Started -> {
                            repository.updateSessionMessages(sessionId) { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantMsgId) msg.copy(status = MessageStatus.STREAMING) else msg
                                }
                            }
                        }

                        is InferenceStreamEvent.Token -> appendParsed(event.text, event.totalTokens, event.tokensPerSecond)

                        is InferenceStreamEvent.Thinking -> appendSmoothly(event.text, true, -1, -1f)

                        is InferenceStreamEvent.Completed -> {
                            thinkingParser.finish().forEach { segment ->
                                appendSmoothly(segment.text, segment.isThinking, event.totalTokens, event.tokensPerSecond)
                            }
                            val finalStatus = when (event.finishReason) {
                                FinishReason.STOPPED_BY_USER -> MessageStatus.STOPPED
                                FinishReason.EOS_TOKEN,
                                FinishReason.MAX_TOKENS,
                                FinishReason.CONTEXT_FULL -> MessageStatus.COMPLETED
                            }
                            repository.updateSessionMessages(sessionId) { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(
                                            content = responseBuffer.toString().ifBlank {
                                                if (thinkingBuffer.isNotBlank()) "[O modelo terminou o raciocínio sem uma resposta final.]"
                                                else if (event.finishReason == FinishReason.STOPPED_BY_USER) "[Interrompido antes do primeiro token. Consulte Engine > Diagnóstico e logs.]"
                                                else "[A geração terminou sem emitir tokens. Consulte Engine > Diagnóstico e logs.]"
                                            },
                                            thinkingContent = thinkingBuffer.toString(),
                                            status = finalStatus,
                                            tokenCount = event.totalTokens,
                                            tokensPerSecond = event.tokensPerSecond,
                                            generationDurationMs = event.durationMs
                                        )
                                    } else msg
                                }
                            }
                        }

                        is InferenceStreamEvent.Error -> {
                            repository.updateSessionMessages(sessionId) { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantMsgId) msg.copy(content = event.message, status = MessageStatus.ERROR) else msg
                                }
                            }
                            statusBannerMessage.value = event.message
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                AppDiagnosticsLogger.warn("INFERENCE", "Coleta cancelada para resposta $assistantMsgId.")
                throw cancelled
            } catch (failure: Throwable) {
                AppDiagnosticsLogger.error("INFERENCE", "Falha ao coletar eventos da engine.", failure)
                repository.updateSessionMessages(sessionId) { messages ->
                    messages.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(content = failure.message ?: "Falha ao receber a resposta da engine.", thinkingContent = thinkingBuffer.toString(), status = MessageStatus.ERROR)
                        } else msg
                    }
                }
                statusBannerMessage.value = failure.message ?: "Falha ao receber a resposta da engine."
            }
        }
    }

    private fun handleExportDiagnostics() {
        viewModelScope.launch {
            try {
                AppDiagnosticsLogger.info("DIAGNOSTICS", "Exportação solicitada na tela de configurações.")
                val fileName = AppDiagnosticsLogger.exportToDownloads(getApplication())
                statusBannerMessage.value = "Logs exportados: Downloads/LocalAI/$fileName"
            } catch (failure: Throwable) {
                AppDiagnosticsLogger.error("DIAGNOSTICS", "Falha ao exportar logs", failure)
                statusBannerMessage.value = "Não foi possível exportar os logs: ${failure.message}"
            }
        }
    }

    private fun handleStopGeneration() {
        AppDiagnosticsLogger.warn(
            "INFERENCE",
            "Solicitação de parada recebida pela ação de parar da interface."
        )
        viewModelScope.launch {
            engine.stopGeneration()
        }
    }

    private companion object { const val STREAM_CHARACTER_DELAY_MS = 9L }

    override fun onCleared() {
        AppDiagnosticsLogger.info(
            "LIFECYCLE",
            "ViewModel finalizado; handle nativo será liberado, mas o GGUF privado permanece salvo."
        )
        generationJob?.cancel()
        if (engine is Closeable) {
            engine.close()
        }
        super.onCleared()
    }
}
