package com.example.engine

import com.example.domain.model.ChatMessage
import com.example.domain.model.EngineTelemetry
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.InferenceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class EngineLifecycleState {
    UNLOADED,
    LOADING_MODEL,
    READY,
    GENERATING,
    ERROR
}

enum class FinishReason {
    EOS_TOKEN,
    MAX_TOKENS,
    STOPPED_BY_USER,
    CONTEXT_FULL
}

sealed interface InferenceStreamEvent {
    data class Started(
        val promptTokens: Int,
        val promptEvalTimeMs: Long
    ) : InferenceStreamEvent

    data class Token(
        val text: String,
        val totalTokens: Int,
        val tokensPerSecond: Float
    ) : InferenceStreamEvent

    data class Completed(
        val finishReason: FinishReason,
        val totalTokens: Int,
        val tokensPerSecond: Float,
        val durationMs: Long
    ) : InferenceStreamEvent

    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : InferenceStreamEvent
}

/**
 * Contract that decouples the UI/state layer from the llama.cpp runtime.
 * The JNI implementation owns the native model and creates an inference context
 * for each generation, then exposes decoded token pieces as a Kotlin Flow.
 */
interface LocalInferenceEngine {
    val engineState: StateFlow<EngineLifecycleState>
    val loadedModel: StateFlow<GgufModelSpec?>
    val telemetry: StateFlow<EngineTelemetry>
    val isNativeBridgeLinked: Boolean
    val supportsGpuOffload: Boolean
        get() = false

    suspend fun loadModel(model: GgufModelSpec, config: InferenceConfig): Result<Unit>
    suspend fun unloadModel()
    fun streamCompletion(
        messages: List<ChatMessage>,
        config: InferenceConfig
    ): Flow<InferenceStreamEvent>

    suspend fun stopGeneration()
}
