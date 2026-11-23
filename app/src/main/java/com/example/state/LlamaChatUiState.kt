package com.example.state

import com.example.domain.model.ConversationSession
import com.example.domain.model.DeviceMemoryInfo
import com.example.domain.model.EngineTelemetry
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.InferenceConfig
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
    val engineState: EngineLifecycleState = EngineLifecycleState.UNLOADED,
    val isNativeBridgeLinked: Boolean = false,
    val gpuOffloadAvailable: Boolean = false,
    val config: InferenceConfig = InferenceConfig(),
    val telemetry: EngineTelemetry = EngineTelemetry(),
    val deviceMemory: DeviceMemoryInfo = DeviceMemoryInfo(
        totalRamMb = 4096,
        availableRamMb = 2048,
        cpuCores = 4,
        primaryAbi = "arm64-v8a",
        isArm64 = true
    ),
    val composerDraft: String = "",
    val isPromptInspectorOpen: Boolean = false,
    val statusBannerMessage: String? = null
) {
    val activeSession: ConversationSession?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()

    val isGenerating: Boolean
        get() = engineState == EngineLifecycleState.GENERATING

    val isLoadingModel: Boolean
        get() = engineState == EngineLifecycleState.LOADING_MODEL

    val canSendPrompt: Boolean
        get() = composerDraft.isNotBlank() &&
            !isGenerating &&
            !isLoadingModel

    val contextUsageFraction: Float
        get() {
            val maxCtx = config.contextLength.coerceAtLeast(1)
            return (telemetry.promptTokensUsed.toFloat() / maxCtx.toFloat()).coerceIn(0f, 1f)
        }
}
