package com.example.engine

import com.example.domain.model.ChatAttachment
import com.example.domain.model.EngineConfig
import com.example.domain.model.GgufModelSpec
import kotlinx.coroutines.flow.Flow

enum class EngineLifecycleState {
    UNLOADED,
    LOADING_MODEL,
    READY,
    GENERATING,
    ERROR
}

sealed interface EngineStreamEvent {
    data class RawToken(val text: String) : EngineStreamEvent
    data class Completed(
        val tokenCount: Int,
        val tokensPerSecond: Float
    ) : EngineStreamEvent
    data class Error(val message: String) : EngineStreamEvent
}

interface LlamaEngineAdapter {
    suspend fun loadModel(model: GgufModelSpec, config: EngineConfig): Result<Unit>
    suspend fun unloadModel()
    fun generateStream(
        formattedPrompt: String,
        model: GgufModelSpec,
        config: EngineConfig,
        attachments: List<ChatAttachment>
    ): Flow<EngineStreamEvent>
    fun stopGeneration()
}
