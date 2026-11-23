package com.example.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.engine.LocalInferenceEngine
import com.example.engine.PromptFormatter
import com.example.engine.StubLlamaEngineAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class LlamaChatViewModel(application: Application) : AndroidViewModel(application) {

    private val hardwareInspector = DeviceHardwareInspector(application.applicationContext)
    private val downloadDispatcher = ModelDownloadDispatcher(application.applicationContext)
    private val initialMemory = hardwareInspector.inspectMemoryAndCpu()
    private val repository = GgufRepository(defaultThreads = (initialMemory.cpuCores / 2).coerceIn(2, 6))
    private val engine: LocalInferenceEngine = StubLlamaEngineAdapter()

    private val currentDestination = MutableStateFlow(AppDestination.CHAT)
    private val composerDraft = MutableStateFlow("")
    private val isPromptInspectorOpen = MutableStateFlow(false)
    private val statusBannerMessage = MutableStateFlow<String?>(null)
    private val deviceMemory = MutableStateFlow(initialMemory)

    private var generationJob: Job? = null

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
            is LlamaChatAction.UpdateGpuLayers -> repository.updateConfig { current ->
                current.copy(gpuLayers = action.layers.coerceIn(0, EngineLimits.MAX_GPU_LAYERS))
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
            is LlamaChatAction.UpdateChatTemplate -> repository.updateConfig { it.copy(chatTemplate = action.format) }
            is LlamaChatAction.UpdateSystemPrompt -> repository.updateConfig { it.copy(systemPrompt = action.prompt) }
            LlamaChatAction.ResetDefaultConfig -> {
                val safeThreads = (deviceMemory.value.cpuCores / 2).coerceIn(2, 6)
                val activeTemplate = engine.loadedModel.value?.defaultTemplate ?: repository.config.value.chatTemplate
                repository.updateConfig {
                    InferenceConfig(
                        threads = safeThreads,
                        chatTemplate = activeTemplate
                    )
                }
                statusBannerMessage.value = "Parâmetros restaurados dentro dos limites do dispositivo."
            }
        }
    }

    fun buildFormattedPromptPreview(): String {
        val state = uiState.value
        val messages = state.activeSession?.messages.orEmpty()
        return PromptFormatter.formatPrompt(messages, state.config)
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
        viewModelScope.launch {
            repository.updateConfig { current ->
                val boundedCtx = current.contextLength.coerceAtMost(model.maxContextLength)
                current.copy(
                    chatTemplate = model.defaultTemplate,
                    contextLength = boundedCtx,
                    batchSize = current.batchSize.coerceAtMost(boundedCtx),
                    maxTokens = current.maxTokens.coerceAtMost(boundedCtx)
                )
            }
            val result = engine.loadModel(model, repository.config.value)
            if (result.isSuccess) {
                statusBannerMessage.value = "Perfil ${model.name} (${model.quantization.label}) selecionado."
            } else {
                statusBannerMessage.value = "Falha ao selecionar perfil: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun handleUnloadModel() {
        viewModelScope.launch {
            generationJob?.cancel()
            engine.unloadModel()
            statusBannerMessage.value = "Perfil descarregado."
        }
    }

    private fun handleImportGgufUri(uri: Uri) {
        val parsed = hardwareInspector.inspectPickedGgufUri(uri)
        if (parsed != null) {
            repository.registerPickedModel(parsed)
            statusBannerMessage.value = "Arquivo ${parsed.fileName} vinculado (${parsed.fileSizeMb} MB)."
            handleLoadModel(parsed)
        } else {
            statusBannerMessage.value = "Não foi possível ler os metadados do arquivo GGUF selecionado."
        }
    }

    private fun handleRemoveImportedModel(modelId: String) {
        if (engine.loadedModel.value?.id == modelId) {
            handleUnloadModel()
        }
        repository.removePickedModel(modelId)
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
                    repository.updateConfig { it.copy(chatTemplate = defaultModel.defaultTemplate) }
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
        generationJob?.cancel()
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

        generationJob = viewModelScope.launch {
            val streamBuffer = StringBuilder()
            engine.streamCompletion(historyForPrompt, repository.config.value).collect { event ->
                when (event) {
                    is InferenceStreamEvent.Started -> {
                        repository.updateSessionMessages(sessionId) { messages ->
                            messages.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(status = MessageStatus.STREAMING)
                                } else msg
                            }
                        }
                    }

                    is InferenceStreamEvent.Token -> {
                        streamBuffer.append(event.text)
                        val latestText = streamBuffer.toString()
                        repository.updateSessionMessages(sessionId) { messages ->
                            messages.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        content = latestText,
                                        status = MessageStatus.STREAMING,
                                        tokenCount = event.totalTokens,
                                        tokensPerSecond = event.tokensPerSecond
                                    )
                                } else msg
                            }
                        }
                    }

                    is InferenceStreamEvent.Completed -> {
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
                                        content = streamBuffer.toString().ifBlank { "[Sem tokens emitidos]" },
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
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        content = event.message,
                                        status = MessageStatus.ERROR
                                    )
                                } else msg
                            }
                        }
                        statusBannerMessage.value = event.message
                    }
                }
            }
        }
    }

    private fun handleStopGeneration() {
        viewModelScope.launch {
            engine.stopGeneration()
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }
}
