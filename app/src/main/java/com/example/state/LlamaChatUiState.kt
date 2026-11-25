package com.example.state

import com.example.domain.model.ChatAttachment
import com.example.domain.model.ConversationSession
import com.example.domain.model.EngineConfig
import com.example.domain.model.EngineLimits
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ModelDownloadProgress
import com.example.engine.EngineLifecycleState

enum class AppDestination {
    CHAT,
    MODELS,
    ENGINE
}

data class LlamaChatUiState(
    val currentDestination: AppDestination = AppDestination.CHAT,
    val sessions: List<ConversationSession> = emptyList(),
    val activeSessionId: String = "",
    val models: List<GgufModelSpec> = emptyList(),
    val loadedModel: GgufModelSpec? = null,
    val appliedConfig: EngineConfig? = null,
    val config: EngineConfig = EngineConfig(),
    val engineState: EngineLifecycleState = EngineLifecycleState.UNLOADED,
    val downloads: Map<String, ModelDownloadProgress> = emptyMap(),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val composerText: String = "",
    val isAttachmentSheetOpen: Boolean = false,
    val isContextSheetOpen: Boolean = false,
    val statusBannerMessage: String? = null
) {
    val activeSession: ConversationSession?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()

    val isGenerating: Boolean
        get() = engineState == EngineLifecycleState.GENERATING

    val isLoadingModel: Boolean
        get() = engineState == EngineLifecycleState.LOADING_MODEL

    val maxModelContext: Int
        get() = loadedModel?.maxContextLength ?: EngineLimits.MAX_CONTEXT

    val hasPendingConfigChanges: Boolean
        get() {
            val applied = appliedConfig ?: return false
            if (loadedModel == null) return false
            return config.contextLength != applied.contextLength ||
                config.maxTokens != applied.maxTokens ||
                config.batchSize != applied.batchSize ||
                config.threads != applied.threads ||
                config.gpuLayers != applied.gpuLayers ||
                config.temperature != applied.temperature ||
                config.topP != applied.topP ||
                config.topK != applied.topK ||
                config.repeatPenalty != applied.repeatPenalty ||
                config.useMmap != applied.useMmap ||
                config.useMlock != applied.useMlock ||
                config.systemPrompt != applied.systemPrompt
        }
}
