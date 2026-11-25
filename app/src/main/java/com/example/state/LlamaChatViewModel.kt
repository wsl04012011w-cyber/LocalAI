package com.example.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDiagnosticsLogger
import com.example.data.AttachmentReader
import com.example.data.GgufRepository
import com.example.data.LocalModelStorageManager
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatRole
import com.example.domain.model.ConversationSession
import com.example.domain.model.EngineConfig
import com.example.domain.model.EngineLimits
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ThinkingEffort
import com.example.domain.model.ThinkingSupportMode
import com.example.engine.EngineLifecycleState
import com.example.engine.EngineStreamEvent
import com.example.engine.LocalInferenceEngine
import com.example.engine.PromptFormatter
import com.example.engine.ThinkingTagParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class LlamaChatViewModel(application: Application) : AndroidViewModel(application) {

    private val storageManager = LocalModelStorageManager(application, viewModelScope)
    private val repository = GgufRepository(storageManager)
    private val attachmentReader = AttachmentReader(application)
    private val inferenceEngine = LocalInferenceEngine()

    private val initialSession = ConversationSession(
        id = UUID.randomUUID().toString(),
        title = "Nova conversa"
    )

    private val _uiState = MutableStateFlow(
        LlamaChatUiState(
            sessions = listOf(initialSession),
            activeSessionId = initialSession.id,
            models = repository.loadModelsSnapshot()
        )
    )
    val uiState: StateFlow<LlamaChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            storageManager.downloads.collect { downloads ->
                _uiState.update { it.copy(downloads = downloads) }
            }
        }
    }

    fun onAction(action: LlamaChatAction) {
        when (action) {
            is LlamaChatAction.SelectDestination -> {
                _uiState.update { it.copy(currentDestination = action.destination) }
            }

            LlamaChatAction.CreateNewSession -> createNewSession()
            is LlamaChatAction.SelectSession -> {
                _uiState.update {
                    it.copy(
                        activeSessionId = action.sessionId,
                        currentDestination = AppDestination.CHAT
                    )
                }
            }
            is LlamaChatAction.DeleteSession -> deleteSession(action.sessionId)

            is LlamaChatAction.UpdateComposerText -> {
                _uiState.update { it.copy(composerText = action.text) }
            }
            LlamaChatAction.SendMessage -> sendMessage()
            LlamaChatAction.StopGeneration -> stopGeneration()
            LlamaChatAction.RegenerateLastResponse -> regenerateLastResponse()
            is LlamaChatAction.ToggleThinkingBox -> toggleThinkingBox(action.messageId)
            LlamaChatAction.CycleThinkingEffort -> cycleThinkingEffort()

            is LlamaChatAction.SetAttachmentSheetOpen -> {
                _uiState.update { it.copy(isAttachmentSheetOpen = action.open) }
            }
            is LlamaChatAction.SetContextSheetOpen -> {
                _uiState.update { it.copy(isContextSheetOpen = action.open) }
            }
            is LlamaChatAction.AddImageAttachmentUri -> handleImageAttachment(action.uri)
            is LlamaChatAction.AddDocumentAttachmentUri -> handleDocumentAttachment(action.uri)
            is LlamaChatAction.RemoveAttachment -> {
                _uiState.update { state ->
                    state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == action.attachmentId })
                }
            }

            is LlamaChatAction.DownloadModel -> startDownload(action.model)
            is LlamaChatAction.CancelDownload -> storageManager.cancelDownload(action.modelId)
            is LlamaChatAction.ImportModelUri -> importModel(action.uri)
            is LlamaChatAction.LoadModel -> loadModel(action.model)
            LlamaChatAction.UnloadModel -> unloadModel()
            is LlamaChatAction.DeleteModel -> deleteModel(action.model)
            LlamaChatAction.ReloadActiveModel -> {
                val active = _uiState.value.loadedModel ?: return
                loadModel(active)
            }

            is LlamaChatAction.UpdateContextLength -> {
                val maxCtx = _uiState.value.maxModelContext
                updateConfig { it.copy(contextLength = action.contextLength.coerceIn(EngineLimits.MIN_CONTEXT, maxCtx)) }
            }
            is LlamaChatAction.UpdateMaxTokens -> {
                updateConfig { it.copy(maxTokens = action.maxTokens.coerceIn(EngineLimits.MIN_MAX_TOKENS, EngineLimits.MAX_MAX_TOKENS)) }
            }
            is LlamaChatAction.UpdateBatchSize -> {
                updateConfig { it.copy(batchSize = action.batchSize.coerceIn(EngineLimits.MIN_BATCH, EngineLimits.MAX_BATCH)) }
            }
            is LlamaChatAction.UpdateThreads -> {
                updateConfig { it.copy(threads = action.threads.coerceIn(EngineLimits.MIN_THREADS, EngineLimits.MAX_THREADS)) }
            }
            is LlamaChatAction.UpdateGpuLayers -> {
                updateConfig { it.copy(gpuLayers = action.gpuLayers.coerceIn(EngineLimits.MIN_GPU_LAYERS, EngineLimits.MAX_GPU_LAYERS)) }
            }
            is LlamaChatAction.UpdateTemperature -> {
                updateConfig { it.copy(temperature = action.temperature.coerceIn(0.0f, 2.0f)) }
            }
            is LlamaChatAction.UpdateTopP -> {
                updateConfig { it.copy(topP = action.topP.coerceIn(0.05f, 1.0f)) }
            }
            is LlamaChatAction.UpdateTopK -> {
                updateConfig { it.copy(topK = action.topK.coerceIn(1, 200)) }
            }
            is LlamaChatAction.UpdateRepeatPenalty -> {
                updateConfig { it.copy(repeatPenalty = action.repeatPenalty.coerceIn(1.0f, 1.5f)) }
            }
            is LlamaChatAction.UpdateUseMmap -> {
                updateConfig { it.copy(useMmap = action.useMmap) }
            }
            is LlamaChatAction.UpdateUseMlock -> {
                updateConfig { it.copy(useMlock = action.useMlock) }
            }
            is LlamaChatAction.UpdateSystemPrompt -> {
                updateConfig { it.copy(systemPrompt = action.systemPrompt) }
            }
            LlamaChatAction.ResetEngineDefaults -> {
                val currentEffort = _uiState.value.config.thinkingEffort
                val defaults = EngineConfig(thinkingEffort = currentEffort)
                _uiState.update { it.copy(config = defaults) }
            }

            is LlamaChatAction.ShowBanner -> {
                _uiState.update { it.copy(statusBannerMessage = action.message) }
            }
            LlamaChatAction.DismissStatusBanner -> {
                _uiState.update { it.copy(statusBannerMessage = null) }
            }
        }
    }

    private inline fun updateConfig(transform: (EngineConfig) -> EngineConfig) {
        _uiState.update { state -> state.copy(config = transform(state.config)) }
    }

    private fun createNewSession() {
        if (_uiState.value.isGenerating) return
        val session = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = "Nova conversa"
        )
        _uiState.update { state ->
            state.copy(
                sessions = listOf(session) + state.sessions,
                activeSessionId = session.id,
                currentDestination = AppDestination.CHAT,
                pendingAttachments = emptyList(),
                composerText = ""
            )
        }
    }

    private fun deleteSession(sessionId: String) {
        _uiState.update { state ->
            if (state.sessions.size <= 1) return@update state
            val remaining = state.sessions.filterNot { it.id == sessionId }
            val nextActive = if (state.activeSessionId == sessionId) remaining.first().id else state.activeSessionId
            state.copy(sessions = remaining, activeSessionId = nextActive)
        }
    }

    private fun cycleThinkingEffort() {
        val state = _uiState.value
        val model = state.loadedModel
        if (model == null) {
            _uiState.update { it.copy(statusBannerMessage = "Carregue um modelo para alternar o raciocínio.") }
            return
        }
        val next = when (model.thinkingSupport) {
            ThinkingSupportMode.NONE -> {
                _uiState.update { it.copy(statusBannerMessage = "${model.name} não possui modo de raciocínio.") }
                return
            }
            ThinkingSupportMode.BINARY -> {
                if (state.config.thinkingEffort.isEnabled) ThinkingEffort.OFF else ThinkingEffort.ON
            }
            ThinkingSupportMode.EFFORT_LEVELS -> {
                when (state.config.thinkingEffort) {
                    ThinkingEffort.OFF -> ThinkingEffort.LOW
                    ThinkingEffort.LOW -> ThinkingEffort.MEDIUM
                    ThinkingEffort.MEDIUM -> ThinkingEffort.HIGH
                    ThinkingEffort.HIGH -> ThinkingEffort.XHIGH
                    ThinkingEffort.XHIGH, ThinkingEffort.ON -> ThinkingEffort.OFF
                }
            }
        }
        _uiState.update { it.copy(config = it.config.copy(thinkingEffort = next)) }
    }

    private fun handleImageAttachment(uri: Uri) {
        val model = _uiState.value.loadedModel
        if (model != null && !model.supportsVision) {
            _uiState.update {
                it.copy(
                    isAttachmentSheetOpen = false,
                    statusBannerMessage = "O modelo atual não suporta imagens."
                )
            }
            return
        }
        viewModelScope.launch {
            val attachment = attachmentReader.readImageAttachment(uri)
            if (attachment != null) {
                _uiState.update { state ->
                    state.copy(
                        pendingAttachments = state.pendingAttachments + attachment,
                        isAttachmentSheetOpen = false
                    )
                }
            }
        }
    }

    private fun handleDocumentAttachment(uri: Uri) {
        viewModelScope.launch {
            val attachment = attachmentReader.readDocumentAttachment(uri)
            if (attachment != null) {
                _uiState.update { state ->
                    state.copy(
                        pendingAttachments = state.pendingAttachments + attachment,
                        isAttachmentSheetOpen = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isAttachmentSheetOpen = false,
                        statusBannerMessage = "Não foi possível ler o arquivo selecionado."
                    )
                }
            }
        }
    }

    private fun startDownload(model: GgufModelSpec) {
        storageManager.startDownload(
            model = model,
            onCompleted = { file ->
                AppDiagnosticsLogger.info("DOWNLOAD", "Modelo salvo em ${file.absolutePath}")
                refreshModels()
                _uiState.update { it.copy(statusBannerMessage = "${model.name} baixado com sucesso.") }
            },
            onError = { errorMsg ->
                AppDiagnosticsLogger.error("DOWNLOAD", "Erro ao baixar ${model.fileName}: $errorMsg")
                _uiState.update { it.copy(statusBannerMessage = "Erro no download: $errorMsg") }
            }
        )
    }

    private fun importModel(uri: Uri) {
        viewModelScope.launch {
            val imported = storageManager.importFromSafUri(uri)
            if (imported != null) {
                refreshModels()
                _uiState.update { it.copy(statusBannerMessage = "Modelo importado: ${imported.name}") }
            } else {
                _uiState.update { it.copy(statusBannerMessage = "Falha ao importar arquivo GGUF.") }
            }
        }
    }

    private fun loadModel(model: GgufModelSpec) {
        if (_uiState.value.isGenerating || _uiState.value.isLoadingModel) return
        viewModelScope.launch {
            val adjustedContext = _uiState.value.config.contextLength
                .coerceIn(EngineLimits.MIN_CONTEXT, model.maxContextLength)
            val adjustedThinking = when (model.thinkingSupport) {
                ThinkingSupportMode.NONE -> ThinkingEffort.OFF
                ThinkingSupportMode.BINARY -> if (_uiState.value.config.thinkingEffort.isEnabled) ThinkingEffort.ON else ThinkingEffort.OFF
                ThinkingSupportMode.EFFORT_LEVELS -> when (_uiState.value.config.thinkingEffort) {
                    ThinkingEffort.ON -> ThinkingEffort.MEDIUM
                    else -> _uiState.value.config.thinkingEffort
                }
            }
            val targetConfig = _uiState.value.config.copy(
                contextLength = adjustedContext,
                thinkingEffort = adjustedThinking
            )

            _uiState.update {
                it.copy(
                    engineState = EngineLifecycleState.LOADING_MODEL,
                    config = targetConfig
                )
            }

            val result = inferenceEngine.loadModel(model, targetConfig)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        loadedModel = model,
                        appliedConfig = targetConfig,
                        engineState = EngineLifecycleState.READY,
                        statusBannerMessage = "${model.name} carregado."
                    )
                }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Falha ao carregar modelo."
                _uiState.update {
                    it.copy(
                        engineState = if (it.loadedModel != null) EngineLifecycleState.READY else EngineLifecycleState.ERROR,
                        statusBannerMessage = msg
                    )
                }
            }
        }
    }

    private fun unloadModel() {
        if (_uiState.value.isGenerating) stopGeneration()
        viewModelScope.launch {
            inferenceEngine.unloadModel()
            _uiState.update {
                it.copy(
                    loadedModel = null,
                    appliedConfig = null,
                    engineState = EngineLifecycleState.UNLOADED,
                    statusBannerMessage = "Modelo descarregado."
                )
            }
        }
    }

    private fun deleteModel(model: GgufModelSpec) {
        if (_uiState.value.loadedModel?.id == model.id) {
            unloadModel()
        }
        storageManager.deleteStoredModel(model.fileName)
        refreshModels()
        _uiState.update { it.copy(statusBannerMessage = "${model.name} removido.") }
    }

    private fun refreshModels() {
        _uiState.update { state ->
            val updatedList = repository.loadModelsSnapshot()
            val refreshedLoaded = state.loadedModel?.let { current ->
                updatedList.firstOrNull { it.id == current.id }
            }
            state.copy(
                models = updatedList,
                loadedModel = refreshedLoaded
            )
        }
    }

    private fun sendMessage() {
        val state = _uiState.value
        val trimmed = state.composerText.trim()
        val attachments = state.pendingAttachments
        if ((trimmed.isEmpty() && attachments.isEmpty()) || state.isGenerating || state.isLoadingModel) return

        val model = state.loadedModel
        if (model == null) {
            _uiState.update {
                it.copy(statusBannerMessage = "Baixe e carregue um modelo na aba Modelos para conversar.")
            }
            return
        }

        val session = state.activeSession ?: return
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = trimmed,
            attachments = attachments
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            role = ChatRole.ASSISTANT,
            content = "",
            isStreaming = true
        )

        val updatedTitle = if (session.messages.isEmpty() && trimmed.isNotEmpty()) {
            trimmed.lineSequence().first().take(36)
        } else {
            session.title
        }

        val historyWithUser = session.messages + userMsg
        updateSessionMessages(session.id, updatedTitle, historyWithUser + assistantPlaceholder)

        _uiState.update {
            it.copy(
                composerText = "",
                pendingAttachments = emptyList()
            )
        }

        streamAssistantResponse(
            sessionId = session.id,
            history = historyWithUser,
            assistantMsgId = assistantMsgId,
            model = model,
            config = state.config,
            attachments = attachments
        )
    }

    private fun regenerateLastResponse() {
        val state = _uiState.value
        if (state.isGenerating || state.isLoadingModel) return
        val model = state.loadedModel ?: run {
            _uiState.update { it.copy(statusBannerMessage = "Carregue um modelo para gerar novamente.") }
            return
        }
        val session = state.activeSession ?: return
        val lastUserIdx = session.messages.indexOfLast { it.role == ChatRole.USER }
        if (lastUserIdx < 0) return

        val trimmedHistory = session.messages.subList(0, lastUserIdx + 1)
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantMsgId,
            role = ChatRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        updateSessionMessages(session.id, session.title, trimmedHistory + placeholder)

        streamAssistantResponse(
            sessionId = session.id,
            history = trimmedHistory,
            assistantMsgId = assistantMsgId,
            model = model,
            config = state.config,
            attachments = trimmedHistory.last().attachments
        )
    }

    private fun streamAssistantResponse(
        sessionId: String,
        history: List<ChatMessage>,
        assistantMsgId: String,
        model: GgufModelSpec,
        config: EngineConfig,
        attachments: List<com.example.domain.model.ChatAttachment>
    ) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(engineState = EngineLifecycleState.GENERATING) }
            val prompt = PromptFormatter.formatPrompt(model, history, config)
            val parser = ThinkingTagParser()
            var thinkingStartMs = 0L

            try {
                inferenceEngine.generateStream(prompt, model, config, attachments).collect { event ->
                    when (event) {
                        is EngineStreamEvent.RawToken -> {
                            val parsed = parser.consume(event.text)
                            if (parsed.enteredThinking && thinkingStartMs == 0L) {
                                thinkingStartMs = System.currentTimeMillis()
                            }
                            val duration = if (parsed.exitedThinking && thinkingStartMs > 0L) {
                                (System.currentTimeMillis() - thinkingStartMs).coerceAtLeast(1L)
                            } else {
                                0L
                            }
                            mutateAssistantMessage(sessionId, assistantMsgId) { msg ->
                                msg.copy(
                                    thinkingContent = msg.thinkingContent + parsed.thinkingDelta,
                                    content = msg.content + parsed.contentDelta,
                                    isThinkingInProgress = when {
                                        parsed.exitedThinking -> false
                                        parsed.enteredThinking -> true
                                        else -> msg.isThinkingInProgress
                                    },
                                    thinkingDurationMs = if (duration > 0L) duration else msg.thinkingDurationMs
                                )
                            }
                        }

                        is EngineStreamEvent.Completed -> {
                            val tail = parser.flush()
                            val finalDuration = if (thinkingStartMs > 0L) {
                                (System.currentTimeMillis() - thinkingStartMs).coerceAtLeast(1L)
                            } else {
                                0L
                            }
                            mutateAssistantMessage(sessionId, assistantMsgId) { msg ->
                                msg.copy(
                                    thinkingContent = msg.thinkingContent + tail.thinkingDelta,
                                    content = msg.content + tail.contentDelta,
                                    isThinkingInProgress = false,
                                    thinkingDurationMs = msg.thinkingDurationMs.takeIf { it > 0L } ?: finalDuration,
                                    isStreaming = false,
                                    generatedTokenCount = event.tokenCount,
                                    tokensPerSecond = event.tokensPerSecond
                                )
                            }
                            _uiState.update { it.copy(engineState = EngineLifecycleState.READY) }
                        }

                        is EngineStreamEvent.Error -> {
                            mutateAssistantMessage(sessionId, assistantMsgId) { msg ->
                                msg.copy(
                                    content = msg.content.ifBlank { "Erro: ${event.message}" },
                                    isThinkingInProgress = false,
                                    isStreaming = false
                                )
                            }
                            _uiState.update {
                                it.copy(
                                    engineState = EngineLifecycleState.READY,
                                    statusBannerMessage = event.message
                                )
                            }
                        }
                    }
                }
            } finally {
                mutateAssistantMessage(sessionId, assistantMsgId) { msg ->
                    if (msg.isStreaming) {
                        msg.copy(isStreaming = false, isThinkingInProgress = false)
                    } else {
                        msg
                    }
                }
                _uiState.update { state ->
                    if (state.engineState == EngineLifecycleState.GENERATING) {
                        state.copy(
                            engineState = if (state.loadedModel != null) EngineLifecycleState.READY else EngineLifecycleState.UNLOADED
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun stopGeneration() {
        inferenceEngine.stopGeneration()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { state ->
            val updatedSessions = state.sessions.map { session ->
                session.copy(
                    messages = session.messages.map { msg ->
                        if (msg.isStreaming || msg.isThinkingInProgress) {
                            msg.copy(isStreaming = false, isThinkingInProgress = false)
                        } else {
                            msg
                        }
                    }
                )
            }
            state.copy(
                sessions = updatedSessions,
                engineState = if (state.loadedModel != null) EngineLifecycleState.READY else EngineLifecycleState.UNLOADED
            )
        }
    }

    private fun toggleThinkingBox(messageId: String) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        mutateAssistantMessage(sessionId, messageId) { msg ->
            msg.copy(isThinkingExpanded = !msg.isThinkingExpanded)
        }
    }

    private fun updateSessionMessages(
        sessionId: String,
        title: String,
        messages: List<ChatMessage>
    ) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(title = title, messages = messages, updatedAtMs = System.currentTimeMillis())
                    } else {
                        s
                    }
                }
            )
        }
    }

    private fun mutateAssistantMessage(
        sessionId: String,
        messageId: String,
        transform: (ChatMessage) -> ChatMessage
    ) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            messages = s.messages.map { m ->
                                if (m.id == messageId) transform(m) else m
                            }
                        )
                    } else {
                        s
                    }
                }
            )
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        inferenceEngine.stopGeneration()
        super.onCleared()
    }
}
